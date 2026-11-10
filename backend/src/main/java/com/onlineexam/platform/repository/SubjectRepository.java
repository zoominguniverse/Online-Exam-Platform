package com.onlineexam.platform.repository;

import com.onlineexam.platform.domain.QuestionBank.Subject;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SubjectRepository {
    private static final String FIELDS = "id, grade_id, name, version, created_at";
    private static final RowMapper<Subject> SUBJECT = (row, index) -> new Subject(
            row.getObject("id", UUID.class), row.getObject("grade_id", UUID.class), row.getString("name"),
            row.getInt("version"), row.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public SubjectRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<Subject> list(UUID grade, int limit, long offset) {
        return jdbc.sql("SELECT " + FIELDS + """
                 FROM subjects WHERE CAST(:grade AS uuid) IS NULL OR grade_id = CAST(:grade AS uuid)
                 ORDER BY name_normalized, id LIMIT :limit OFFSET :offset
                """).param("grade", grade).param("limit", limit).param("offset", offset).query(SUBJECT).list();
    }

    public long count(UUID grade) {
        return jdbc.sql("SELECT count(*) FROM subjects WHERE CAST(:grade AS uuid) IS NULL OR grade_id = CAST(:grade AS uuid)")
                .param("grade", grade).query(Long.class).single();
    }

    public Optional<Subject> get(UUID id) {
        return jdbc.sql("SELECT " + FIELDS + " FROM subjects WHERE id = :id").param("id", id).query(SUBJECT).optional();
    }

    public Subject insert(UUID grade, String name, String normalized) {
        return jdbc.sql("""
                INSERT INTO subjects (id, grade_id, name, name_normalized)
                VALUES (:id, :grade, :name, :normalized) RETURNING
                """ + FIELDS).param("id", UUID.randomUUID()).param("grade", grade)
                .param("name", name).param("normalized", normalized).query(SUBJECT).single();
    }

    public Optional<Subject> update(UUID id, int version, String name, String normalized) {
        return jdbc.sql("""
                UPDATE subjects SET name = :name, name_normalized = :normalized, version = version + 1
                WHERE id = :id AND version = :version RETURNING
                """ + FIELDS).param("id", id).param("version", version).param("name", name)
                .param("normalized", normalized).query(SUBJECT).optional();
    }

    public int delete(UUID id, int version) {
        return jdbc.sql("DELETE FROM subjects WHERE id = :id AND version = :version")
                .param("id", id).param("version", version).update();
    }
}
