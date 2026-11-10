package com.onlineexam.platform.repository;

import com.onlineexam.platform.domain.Identity.Account;
import com.onlineexam.platform.domain.Identity.Role;
import com.onlineexam.platform.domain.Identity.Status;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private static final String FIELDS = "id, username, password_hash, role, status, grade_id, version, created_at";
    private static final RowMapper<Account> ACCOUNT = (row, index) -> new Account(
            row.getObject("id", UUID.class), row.getString("username"), row.getString("password_hash"),
            Role.valueOf(row.getString("role")), Status.valueOf(row.getString("status")),
            row.getObject("grade_id", UUID.class), row.getInt("version"),
            row.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public boolean gradeEnabled(UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM grades WHERE id = :id AND enabled)")
                .param("id", id).query(Boolean.class).single();
    }

    public Optional<Account> byId(UUID id) {
        return jdbc.sql("SELECT " + FIELDS + " FROM users WHERE id = :id")
                .param("id", id).query(ACCOUNT).optional();
    }

    public Optional<Account> lockAccount(UUID id) {
        return jdbc.sql("SELECT " + FIELDS + " FROM users WHERE id = :id FOR UPDATE")
                .param("id", id).query(ACCOUNT).optional();
    }

    public void updatePassword(UUID id, String hash) {
        jdbc.sql("""
                UPDATE users SET password_hash = :hash, version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """).param("id", id).param("hash", hash).update();
    }

    public Optional<Account> byUsernameForUpdate(String normalized) {
        return jdbc.sql("SELECT " + FIELDS + " FROM users WHERE username_normalized = :name FOR UPDATE")
                .param("name", normalized).query(ACCOUNT).optional();
    }

    public Account insert(UUID id, String username, String normalized, String hash, Role role, UUID grade) {
        return jdbc.sql("""
                INSERT INTO users (id, username, username_normalized, password_hash, role, status, grade_id)
                VALUES (:id, :username, :normalized, :hash, :role, 'ENABLED', :grade)
                RETURNING """ + " " + FIELDS).param("id", id).param("username", username)
                .param("normalized", normalized).param("hash", hash).param("role", role.name())
                .param("grade", grade).query(ACCOUNT).single();
    }

    public void lockAdministration() {
        jdbc.sql("SELECT id FROM identity_guard WHERE id = 1 FOR UPDATE").query(Integer.class).single();
    }

    public int enabledAdmins() {
        return jdbc.sql("SELECT count(*) FROM users WHERE role = 'ADMIN' AND status = 'ENABLED'")
                .query(Integer.class).single();
    }

    public Account update(Account account, String username, String normalized, Role role, Status status, UUID grade) {
        return jdbc.sql("""
                UPDATE users SET username = :username, username_normalized = :normalized, role = :role,
                    status = :status, grade_id = :grade, version = version + 1, updated_at = CURRENT_TIMESTAMP,
                    password_hash = CASE WHEN :status = 'DELETED' THEN NULL ELSE password_hash END
                WHERE id = :id AND version = :version
                RETURNING """ + " " + FIELDS)
                .param("id", account.id()).param("version", account.version()).param("username", username)
                .param("normalized", normalized).param("role", role.name()).param("status", status.name())
                .param("grade", grade).query(ACCOUNT).single();
    }

    private static final String FILTER = """
            FROM users WHERE status <> 'DELETED'
            AND (:role IS NULL OR role = :role) AND (CAST(:grade AS uuid) IS NULL OR grade_id = CAST(:grade AS uuid))
            """;

    private JdbcClient.StatementSpec filter(String sql, Role role, UUID grade) {
        return jdbc.sql(sql).param("role", role == null ? null : role.name(), java.sql.Types.VARCHAR)
                .param("grade", grade, java.sql.Types.OTHER);
    }

    public List<Account> list(Role role, UUID grade, int pageSize, long offset, String sort) {
        String order = switch (sort) {
            case "username" -> "username_normalized";
            case "createdAt" -> "created_at";
            default -> throw new IllegalArgumentException("Invalid account sort field");
        };
        return filter("SELECT " + FIELDS + " " + FILTER + " ORDER BY " + order + ", id LIMIT :limit OFFSET :offset",
                role, grade).param("limit", pageSize).param("offset", offset).query(ACCOUNT).list();
    }

    public long count(Role role, UUID grade) {
        return filter("SELECT count(*) " + FILTER, role, grade).query(Long.class).single();
    }
}
