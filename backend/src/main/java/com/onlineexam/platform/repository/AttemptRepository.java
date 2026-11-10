package com.onlineexam.platform.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.platform.domain.Paper.Type;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AttemptRepository {
    public record Eligible(UUID versionId, Type type, int durationMinutes,
                           OffsetDateTime opensAt, OffsetDateTime closesAt) {}
    public record Attempt(UUID id, UUID paperId, UUID versionId, String status,
                          OffsetDateTime startedAt, OffsetDateTime deadlineAt, int version) {}
    public record StartKey(UUID paperId, UUID attemptId, OffsetDateTime expiresAt) {}
    public record TaskPaper(UUID versionId, int durationMinutes) {}
    public record Question(int position, int groupPosition, String groupName, UUID questionId,
                           int questionVersion, int scoreMinor, String type, String stemHtml, JsonNode options,
                           int gapCount) {}
    public record SavedAnswer(UUID questionId, JsonNode answer) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AttemptRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<Eligible> eligible(UUID paperId, UUID grade) {
        return jdbc.sql("""
                SELECT p.published_id, p.type, v.duration_minutes, v.opens_at, v.closes_at
                FROM papers p JOIN subjects s ON s.id = p.subject_id
                JOIN grades g ON g.id = s.grade_id
                JOIN paper_versions v ON v.id = p.published_id
                WHERE p.id = :paper AND g.id = :grade AND g.enabled
                  AND NOT p.archived AND p.type IN ('FIXED', 'TIMED')
                FOR SHARE OF p
                """).param("paper", paperId).param("grade", grade).query((row, index) -> new Eligible(
                    row.getObject("published_id", UUID.class), Type.valueOf(row.getString("type")),
                    row.getInt("duration_minutes"), row.getObject("opens_at", OffsetDateTime.class),
                    row.getObject("closes_at", OffsetDateTime.class))).optional();
    }

    public Optional<StartKey> startKey(UUID owner, String key) {
        return jdbc.sql("SELECT paper_id, attempt_id, expires_at FROM attempt_start_keys "
                + "WHERE owner_id = :owner AND key = :key FOR UPDATE")
                .param("owner", owner).param("key", key).query((row, index) -> new StartKey(
                    row.getObject("paper_id", UUID.class), row.getObject("attempt_id", UUID.class),
                    row.getObject("expires_at", OffsetDateTime.class))).optional();
    }

    public void deleteKey(UUID owner, String key) {
        jdbc.sql("DELETE FROM attempt_start_keys WHERE owner_id = :owner AND key = :key")
                .param("owner", owner).param("key", key).update();
    }

    public void insert(UUID id, UUID owner, UUID paper, UUID version, OffsetDateTime now,
                       OffsetDateTime deadline) {
        insert(id, owner, paper, version, null, now, deadline);
    }

    public void insert(UUID id, UUID owner, UUID paper, UUID version, UUID task, OffsetDateTime now,
                       OffsetDateTime deadline) {
        jdbc.sql("""
                INSERT INTO attempts(id, owner_id, paper_id, paper_version_id, task_id, started_at, deadline_at)
                VALUES (:id, :owner, :paper, :version, :task, :now, :deadline)
                """).param("id", id).param("owner", owner).param("paper", paper).param("task", task)
                .param("version", version).param("now", now).param("deadline", deadline).update();
    }

    public boolean insertTaskIfAbsent(UUID id, UUID owner, UUID task, UUID paper, UUID version,
                                      OffsetDateTime now, OffsetDateTime deadline) {
        return jdbc.sql("""
                INSERT INTO attempts(id, owner_id, task_id, paper_id, paper_version_id, started_at, deadline_at)
                VALUES (:id, :owner, :task, :paper, :version, :now, :deadline)
                ON CONFLICT (owner_id, paper_id) WHERE task_id IS NOT NULL DO NOTHING
                """).param("id", id).param("owner", owner).param("task", task).param("paper", paper)
                .param("version", version).param("now", now).param("deadline", deadline).update() == 1;
    }

    public Optional<Attempt> taskAttempt(UUID owner, UUID task, UUID paper) {
        return jdbc.sql("""
                SELECT id, paper_id, paper_version_id, status, started_at, deadline_at, version
                FROM attempts WHERE owner_id = :owner AND task_id = :task AND paper_id = :paper
                """).param("owner", owner).param("task", task).param("paper", paper)
                .query((row, index) -> new Attempt(row.getObject("id", UUID.class),
                        row.getObject("paper_id", UUID.class), row.getObject("paper_version_id", UUID.class),
                        row.getString("status"), row.getObject("started_at", OffsetDateTime.class),
                        row.getObject("deadline_at", OffsetDateTime.class), row.getInt("version"))).optional();
    }

    public Optional<TaskPaper> eligibleTaskPaper(UUID owner, UUID task, UUID paper) {
        return jdbc.sql("""
                SELECT tp.paper_version_id, v.duration_minutes
                FROM tasks t JOIN task_recipients r ON r.task_id = t.id AND r.user_id = :owner
                JOIN task_papers tp ON tp.task_id = t.id AND tp.paper_id = :paper
                JOIN papers p ON p.id = tp.paper_id AND p.type = 'TASK'
                JOIN paper_versions v ON v.id = tp.paper_version_id
                WHERE t.id = :task AND t.state = 'PUBLISHED' AND NOT p.archived
                FOR SHARE OF t, p
                """).param("owner", owner).param("task", task).param("paper", paper)
                .query((row, index) -> new TaskPaper(row.getObject("paper_version_id", UUID.class),
                        row.getInt("duration_minutes"))).optional();
    }

    public int snapshotItems(UUID attempt, UUID paperVersion) {
        return jdbc.sql("""
                INSERT INTO attempt_items(attempt_id, position, group_position, group_name, question_id,
                    question_version, score_minor, type, stem_html, options, correct_answer, explanation_html)
                SELECT :attempt, i.position, i.group_position, i.group_name, i.question_id,
                    i.question_version, i.score_minor, q.type, q.stem_html, q.options, q.answer, q.explanation_html
                FROM paper_items i JOIN question_versions q
                  ON q.question_id = i.question_id AND q.version = i.question_version
                WHERE i.paper_version_id = :version
                """).param("attempt", attempt).param("version", paperVersion).update();
    }

    public void insertKey(UUID owner, String key, UUID paper, UUID attempt, OffsetDateTime expiry) {
        jdbc.sql("""
                INSERT INTO attempt_start_keys(owner_id, key, paper_id, attempt_id, expires_at)
                VALUES (:owner, :key, :paper, :attempt, :expiry)
                """).param("owner", owner).param("key", key).param("paper", paper)
                .param("attempt", attempt).param("expiry", expiry).update();
    }

    public Optional<Attempt> owned(UUID owner, UUID attempt) {
        return owned(owner, attempt, false);
    }

    public Optional<Attempt> owned(UUID owner, UUID attempt, boolean lock) {
        return jdbc.sql("""
                SELECT id, paper_id, paper_version_id, status, started_at, deadline_at, version
                FROM attempts WHERE id = :attempt AND owner_id = :owner
                """ + (lock ? " FOR UPDATE" : "")).param("owner", owner).param("attempt", attempt).query((row, index) -> new Attempt(
                    row.getObject("id", UUID.class), row.getObject("paper_id", UUID.class),
                    row.getObject("paper_version_id", UUID.class), row.getString("status"),
                    row.getObject("started_at", OffsetDateTime.class),
                    row.getObject("deadline_at", OffsetDateTime.class), row.getInt("version"))).optional();
    }

    public List<Question> questions(UUID attempt) {
        return jdbc.sql("""
                SELECT position, group_position, group_name, question_id, question_version,
                       score_minor, type, stem_html, options,
                       CASE WHEN type = 'GAP' THEN jsonb_array_length(correct_answer->'gaps') ELSE 0 END AS gap_count
                FROM attempt_items WHERE attempt_id = :attempt ORDER BY position
                """).param("attempt", attempt).query((row, index) -> new Question(
                    row.getInt("position"), row.getInt("group_position"), row.getString("group_name"),
                    row.getObject("question_id", UUID.class), row.getInt("question_version"),
                    row.getInt("score_minor"), row.getString("type"), row.getString("stem_html"),
                    decode(row.getString("options")), row.getInt("gap_count"))).list();
    }

    public List<SavedAnswer> answers(UUID attempt) {
        return jdbc.sql("SELECT question_id, answer FROM attempt_answers WHERE attempt_id = :attempt ORDER BY question_id")
                .param("attempt", attempt).query((row, index) -> new SavedAnswer(
                        row.getObject("question_id", UUID.class), decode(row.getString("answer")))).list();
    }

    public void replaceAnswers(UUID attempt, List<SavedAnswer> answers) {
        jdbc.sql("DELETE FROM attempt_answers WHERE attempt_id = :attempt").param("attempt", attempt).update();
        for (SavedAnswer item : answers) {
            jdbc.sql("INSERT INTO attempt_answers(attempt_id, question_id, answer) "
                    + "VALUES (:attempt, :question, CAST(:answer AS jsonb))")
                    .param("attempt", attempt).param("question", item.questionId())
                    .param("answer", item.answer().toString()).update();
        }
        jdbc.sql("UPDATE attempts SET version = version + 1 WHERE id = :attempt")
                .param("attempt", attempt).update();
    }

    private JsonNode decode(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot read frozen question options", error);
        }
    }
}
