package com.onlineexam.platform.repository;

import com.onlineexam.platform.domain.Identity.Role;
import com.onlineexam.platform.integration.WeChatIdentityProvider.Subject;
import com.onlineexam.platform.repository.SessionRepository.Principal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class WeChatRepository {
    private final JdbcClient jdbc;

    public WeChatRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public boolean consume(String hash, OffsetDateTime now) {
        jdbc.sql("DELETE FROM wechat_used_codes WHERE expires_at <= :now").param("now", now).update();
        return jdbc.sql("""
                INSERT INTO wechat_used_codes (code_hash, expires_at) VALUES (:hash, :expiry)
                ON CONFLICT DO NOTHING
                """).param("hash", hash).param("expiry", now.plusMinutes(10)).update() == 1;
    }

    public boolean bind(UUID binding, UUID user, Subject subject) {
        return jdbc.sql("""
                INSERT INTO wechat_bindings (id, user_id, app_id, open_id) VALUES (:id, :user, :app, :open)
                ON CONFLICT DO NOTHING
                """).param("id", binding).param("user", user).param("app", subject.appId())
                .param("open", subject.openId()).update() == 1;
    }

    public Optional<UUID> boundUser(Subject subject) {
        return jdbc.sql("SELECT user_id FROM wechat_bindings WHERE app_id = :app AND open_id = :open")
                .param("app", subject.appId()).param("open", subject.openId()).query(UUID.class).optional();
    }

    public Optional<UUID> bindingForUser(UUID user, Subject subject) {
        return jdbc.sql("SELECT id FROM wechat_bindings WHERE user_id = :user AND app_id = :app AND open_id = :open")
                .param("user", user).param("app", subject.appId()).param("open", subject.openId()).query(UUID.class).optional();
    }

    public void createToken(UUID binding, String hash, OffsetDateTime now) {
        jdbc.sql("INSERT INTO wechat_tokens (binding_id, token_hash, expires_at) VALUES (:binding, :hash, :expiry)")
                .param("binding", binding).param("hash", hash).param("expiry", now.plusHours(12)).update();
    }

    public Optional<Principal> findToken(String hash, OffsetDateTime now) {
        return jdbc.sql("""
                SELECT u.id, b.id AS binding_id FROM wechat_tokens t JOIN wechat_bindings b ON b.id = t.binding_id
                JOIN users u ON b.user_id = u.id WHERE t.token_hash = :hash AND t.expires_at > :now
                    AND u.status = 'ENABLED' AND u.role = 'STUDENT'
                """).param("hash", hash).param("now", now)
                .query((row, index) -> new Principal(row.getObject("id", UUID.class), Role.STUDENT,
                        row.getObject("binding_id", UUID.class))).optional();
    }

    public boolean unlink(UUID user, UUID binding) {
        return jdbc.sql("DELETE FROM wechat_bindings WHERE user_id = :user AND id = :binding")
                .param("user", user).param("binding", binding).update() == 1;
    }

    public void revokeUser(UUID user) {
        jdbc.sql("DELETE FROM wechat_tokens WHERE binding_id IN (SELECT id FROM wechat_bindings WHERE user_id = :user)")
                .param("user", user).update();
    }

    public void removeBinding(UUID user) {
        jdbc.sql("DELETE FROM wechat_bindings WHERE user_id = :user").param("user", user).update();
    }
}
