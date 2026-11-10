package com.onlineexam.platform.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.platform.domain.QuestionBank.Answer;
import com.onlineexam.platform.domain.QuestionBank.Draft;
import com.onlineexam.platform.domain.QuestionBank.Option;
import com.onlineexam.platform.domain.QuestionBank.Summary;
import com.onlineexam.platform.domain.QuestionBank.Type;
import com.onlineexam.platform.domain.QuestionBank.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class QuestionRepository {
    private static final String ACTIVE = """
            FROM questions q JOIN question_versions v ON q.id = v.question_id AND q.current_version = v.version
            WHERE NOT q.archived AND (CAST(:subject AS uuid) IS NULL OR q.subject_id = CAST(:subject AS uuid))
            AND (:type IS NULL OR v.type = :type)
            """;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public QuestionRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(UUID id, UUID subject) {
        jdbc.sql("INSERT INTO questions (id, subject_id, current_version) VALUES (:id, :subject, 1)")
                .param("id", id).param("subject", subject).update();
    }

    public void insertVersion(UUID id, int version, Draft draft, int score) {
        jdbc.sql("""
                INSERT INTO question_versions (question_id, version, subject_id, type, difficulty, score_minor,
                    stem_html, options, answer, explanation_html)
                VALUES (:id, :version, :subject, :type, :difficulty, :score, :stem,
                    CAST(:options AS jsonb), CAST(:answer AS jsonb), :explanation)
                """).param("id", id).param("version", version).param("subject", draft.subjectId())
                .param("type", draft.type().name()).param("difficulty", draft.difficulty()).param("score", score)
                .param("stem", draft.stemHtml()).param("options", encode(draft.options()))
                .param("answer", encode(draft.answer())).param("explanation", draft.explanationHtml()).update();
    }

    public void advance(UUID id, int version, UUID subject) {
        jdbc.sql("UPDATE questions SET current_version = :version, subject_id = :subject WHERE id = :id")
                .param("id", id).param("version", version).param("subject", subject).update();
    }

    public Optional<Integer> lockActive(UUID id) {
        return jdbc.sql("SELECT current_version FROM questions WHERE id = :id AND NOT archived FOR UPDATE")
                .param("id", id).query(Integer.class).optional();
    }

    public void archive(UUID id) {
        jdbc.sql("UPDATE questions SET archived = true WHERE id = :id").param("id", id).update();
    }

    public Optional<Version> get(UUID id, Integer version) {
        return jdbc.sql("""
                SELECT v.*, q.archived FROM questions q JOIN question_versions v ON q.id = v.question_id
                WHERE q.id = :id AND v.version = COALESCE(CAST(:version AS integer), q.current_version)
                """).param("id", id).param("version", version).query((row, index) -> new Version(
                id, row.getInt("version"), row.getObject("subject_id", UUID.class),
                Type.valueOf(row.getString("type")), row.getInt("difficulty"), decimal(row.getInt("score_minor")),
                row.getString("stem_html"), decode(row.getString("options"), new TypeReference<List<Option>>() {}),
                decode(row.getString("answer"), new TypeReference<Answer>() {}), row.getString("explanation_html"),
                row.getBoolean("archived"), row.getObject("created_at", OffsetDateTime.class))).optional();
    }

    public List<Summary> list(UUID subject, Type type, int limit, long offset) {
        return filter("""
                SELECT q.id, v.version, v.subject_id, v.type, v.difficulty, v.score_minor, v.stem_html, q.created_at
                """ + ACTIVE + " ORDER BY q.created_at DESC, q.id LIMIT :limit OFFSET :offset", subject, type)
                .param("limit", limit).param("offset", offset).query((row, index) -> new Summary(
                        row.getObject("id", UUID.class), row.getInt("version"), row.getObject("subject_id", UUID.class),
                        Type.valueOf(row.getString("type")), row.getInt("difficulty"), decimal(row.getInt("score_minor")),
                        preview(row.getString("stem_html")), row.getObject("created_at", OffsetDateTime.class))).list();
    }

    public long count(UUID subject, Type type) {
        return filter("SELECT count(*) " + ACTIVE, subject, type).query(Long.class).single();
    }

    private JdbcClient.StatementSpec filter(String sql, UUID subject, Type type) {
        return jdbc.sql(sql).param("subject", subject).param("type", type == null ? null : type.name(), java.sql.Types.VARCHAR);
    }

    private static String decimal(int minor) { return BigDecimal.valueOf(minor, 2).toPlainString(); }

    private static String preview(String html) {
        String plain = Jsoup.parseBodyFragment(html).text();
        return plain.codePointCount(0, plain.length()) <= 80 ? plain
                : plain.substring(0, plain.offsetByCodePoints(0, 80)) + "…";
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot serialize question content", error);
        }
    }

    private <T> T decode(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot read stored question content", error);
        }
    }
}
