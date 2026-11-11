package com.onlineexam.platform.repository;

import com.onlineexam.platform.domain.Identity.Role;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {
    private final JdbcClient jdbc;
    public record Principal(UUID id, Role role) {}

    public SessionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void create(String hash, UUID id, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO web_sessions (token_hash, user_id, last_seen_at, expires_at)
                VALUES (:hash, :id, :now, :expiry)
                """).param("hash", hash).param("id", id).param("now", now)
                .param("expiry", now.plusHours(12)).update();
    }

    public Optional<Principal> find(String hash, OffsetDateTime now) {
        return jdbc.sql("""
                UPDATE web_sessions s SET last_seen_at = GREATEST(s.last_seen_at, :now)
                FROM users u WHERE s.user_id = u.id AND s.token_hash = :hash AND u.status = 'ENABLED'
                    AND s.expires_at > :now AND s.last_seen_at > :idle
                RETURNING u.id, u.role
                """).param("hash", hash).param("now", now).param("idle", now.minusMinutes(30))
                .query((row, index) -> new Principal(row.getObject("id", UUID.class),
                        Role.valueOf(row.getString("role")))).optional();
    }

    public void revoke(String hash) {
        jdbc.sql("DELETE FROM web_sessions WHERE token_hash = :hash").param("hash", hash).update();
    }

    public void revokeUser(UUID id) {
        jdbc.sql("DELETE FROM web_sessions WHERE user_id = :id").param("id", id).update();
    }

    // Old migrations and previous application versions retain these tables.
    // Never authenticate with their contents; only revoke them during account lifecycle changes.
    public void revokeLegacyCredentials(UUID user, boolean removeBinding) {
        jdbc.sql("DELETE FROM wechat_tokens WHERE binding_id IN (SELECT id FROM wechat_bindings WHERE user_id=:user)")
                .param("user",user).update();
        if(removeBinding) jdbc.sql("DELETE FROM wechat_bindings WHERE user_id=:user").param("user",user).update();
    }

    public int lockFailures(String key, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO login_limits (bucket, window_start, failures) VALUES (:key, :now, 0)
                ON CONFLICT (bucket) DO UPDATE SET
                    window_start = CASE WHEN login_limits.window_start <= :start THEN :now ELSE login_limits.window_start END,
                    failures = CASE WHEN login_limits.window_start <= :start THEN 0 ELSE login_limits.failures END
                """).param("key", key).param("now", now).param("start", now.minusMinutes(5)).update();
        return jdbc.sql("SELECT failures FROM login_limits WHERE bucket = :key")
                .param("key", key).query(Integer.class).single();
    }

    public void addFailure(String key) {
        jdbc.sql("UPDATE login_limits SET failures = failures + 1 WHERE bucket = :key").param("key", key).update();
    }
}
