package com.onlineexam.platform.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.platform.domain.Paper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LearningRepository {
    public record ReviewItem(int position, UUID questionId, int questionVersion, String type, String score,
                            String stemHtml, JsonNode options, JsonNode answer, JsonNode referenceAnswer,
                            String explanationHtml, String awardedScore, boolean subjective, boolean empty) {}
    public record Summary(UUID id, UUID ownerId, String username, UUID paperId, UUID subjectId, String paperName,
                          String status, int version, OffsetDateTime startedAt, OffsetDateTime deadlineAt,
                          OffsetDateTime frozenAt, long elapsedSeconds, String automaticScore, String finalScore, String gradingSource) {}
    public record Key(UUID targetId, String hash, OffsetDateTime expiresAt) {}
    public record Metadata(String paperName,String username,OffsetDateTime startedAt,long elapsedSeconds,OffsetDateTime confirmedAt) {}
    public record SubjectInfo(UUID subjectId,String subjectName) {}
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public LearningRepository(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public Optional<UUID> owner(UUID id) {
        return jdbc.sql("SELECT owner_id FROM attempts WHERE id=:id").param("id", id).query(UUID.class).optional();
    }
    public Metadata metadata(UUID id) {
        return jdbc.sql("""
                SELECT v.name,u.username,u.status,a.started_at,a.deadline_at,a.frozen_at,g.confirmed_at
                FROM attempts a JOIN users u ON u.id=a.owner_id JOIN paper_versions v ON v.id=a.paper_version_id
                LEFT JOIN gradings g ON g.attempt_id=a.id WHERE a.id=:id
                """).param("id",id).query((row,index)->{
                    var started=row.getObject("started_at",OffsetDateTime.class);var deadline=row.getObject("deadline_at",OffsetDateTime.class);var frozen=row.getObject("frozen_at",OffsetDateTime.class);
                    return new Metadata(row.getString("name"),"DELETED".equals(row.getString("status"))?"已删除账号":row.getString("username"),started,
                            frozen==null?0:Math.max(0,java.time.Duration.between(started,frozen.isAfter(deadline)?deadline:frozen).getSeconds()),row.getObject("confirmed_at",OffsetDateTime.class));
                }).single();
    }
    public SubjectInfo mistakeSubject(UUID id,UUID question) {
        return jdbc.sql("SELECT s.id,s.name FROM attempt_items i JOIN question_versions v ON v.question_id=i.question_id AND v.version=i.question_version JOIN subjects s ON s.id=v.subject_id WHERE i.attempt_id=:id AND i.question_id=:question")
                .param("id",id).param("question",question).query((row,index)->new SubjectInfo(row.getObject("id",UUID.class),row.getString("name"))).single();
    }
    public List<ReviewItem> review(UUID id) {
        return jdbc.sql("""
                SELECT i.*, a.answer, r.score_minor AS awarded FROM attempt_items i
                LEFT JOIN attempt_answers a ON a.attempt_id=i.attempt_id AND a.question_id=i.question_id
                LEFT JOIN attempt_results r ON r.attempt_id=i.attempt_id AND r.question_id=i.question_id
                WHERE i.attempt_id=:id ORDER BY i.position
                """).param("id", id).query((row, index) -> {
                    JsonNode answer = decode(row.getString("answer"));
                    boolean subjective = List.of("GAP", "SHORT").contains(row.getString("type"));
                    boolean empty = answer == null || ("SHORT".equals(row.getString("type"))
                            ? answer.path("text").asText().isBlank() : "GAP".equals(row.getString("type"))
                                && java.util.stream.StreamSupport.stream(answer.path("texts").spliterator(), false).allMatch(item -> item.asText().isBlank()));
                    Integer score = row.getObject("awarded", Integer.class);
                    return new ReviewItem(row.getInt("position"), row.getObject("question_id", UUID.class), row.getInt("question_version"),
                            row.getString("type"), Paper.score(row.getInt("score_minor")), row.getString("stem_html"), decode(row.getString("options")),
                            answer, decode(row.getString("correct_answer")), row.getString("explanation_html"), score == null ? null : Paper.score(score), subjective, empty);
                }).list();
    }
    private static final String FROM = """
            FROM attempts a JOIN users u ON u.id=a.owner_id JOIN papers p ON p.id=a.paper_id
            JOIN paper_versions v ON v.id=a.paper_version_id LEFT JOIN gradings g ON g.attempt_id=a.id
            WHERE (CAST(:owner AS uuid) IS NULL OR a.owner_id=CAST(:owner AS uuid))
            AND (CAST(:subject AS uuid) IS NULL OR p.subject_id=CAST(:subject AS uuid))
            AND (:status IS NULL OR a.status=:status)
            """;
    public List<Summary> list(UUID owner, UUID subject, String status, int size, long offset) {
        return filter("SELECT a.*, u.username, u.status AS user_status, p.subject_id, v.name, g.source " + FROM
                + " ORDER BY a.started_at DESC,a.id LIMIT :size OFFSET :offset", owner, subject, status)
                .param("size", size).param("offset", offset).query((row, index) -> {
                    OffsetDateTime started = row.getObject("started_at", OffsetDateTime.class);
                    OffsetDateTime frozen = row.getObject("frozen_at", OffsetDateTime.class);
                    OffsetDateTime deadline = row.getObject("deadline_at", OffsetDateTime.class);
                    var end = frozen == null ? started : frozen.isAfter(deadline) ? deadline : frozen;
                    Integer automatic = row.getObject("automatic_score_minor", Integer.class);
                    Integer total = row.getObject("final_score_minor", Integer.class);
                    return new Summary(row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class),
                            "DELETED".equals(row.getString("user_status")) ? "已删除账号" : row.getString("username"),
                            row.getObject("paper_id", UUID.class), row.getObject("subject_id", UUID.class), row.getString("name"),
                            row.getString("status"), row.getInt("version"), started, deadline, frozen,
                            Math.max(0, java.time.Duration.between(started, end).getSeconds()), automatic == null ? null : Paper.score(automatic),
                            total == null ? null : Paper.score(total), row.getString("source") == null ? "AUTO" : row.getString("source"));
                }).list();
    }
    public long count(UUID owner, UUID subject, String status) {
        return filter("SELECT count(*) " + FROM, owner, subject, status).query(Long.class).single();
    }
    private JdbcClient.StatementSpec filter(String sql, UUID owner, UUID subject, String status) {
        return jdbc.sql(sql).param("owner", owner).param("subject", subject).param("status", status, java.sql.Types.VARCHAR);
    }
    public Optional<Key> key(UUID actor, String operation, String key) {
        // The caller already serializes this actor through the account lock; keys are insert/delete only.
        return jdbc.sql("SELECT target_id,request_hash,expires_at FROM operation_keys WHERE actor_id=:actor AND operation=:operation AND key=:key")
                .param("actor", actor).param("operation", operation).param("key", key).query((row,index) -> new Key(
                        row.getObject("target_id",UUID.class),row.getString("request_hash"),row.getObject("expires_at",OffsetDateTime.class))).optional();
    }
    public void deleteKey(UUID actor, String operation, String key) {
        jdbc.sql("DELETE FROM operation_keys WHERE actor_id=:actor AND operation=:operation AND key=:key")
                .param("actor",actor).param("operation",operation).param("key",key).update();
    }
    public void putKey(UUID actor, String operation, String key, UUID target, String hash, OffsetDateTime now) {
        jdbc.sql("INSERT INTO operation_keys(actor_id,operation,key,target_id,request_hash,expires_at) VALUES (:actor,:operation,:key,:target,:hash,:expiry)")
                .param("actor",actor).param("operation",operation).param("key",key).param("target",target).param("hash",hash).param("expiry",now.plusHours(24)).update();
    }
    public void score(UUID id, UUID question, int score) {
        jdbc.sql("UPDATE attempt_results SET score_minor=:score WHERE attempt_id=:id AND question_id=:question")
                .param("id",id).param("question",question).param("score",score).update();
    }
    public void complete(UUID id, UUID owner, int score, OffsetDateTime now) {
        jdbc.sql("INSERT INTO gradings(attempt_id,owner_id,source,confirmed_at,final_score_minor) VALUES (:id,:owner,'SELF',:now,:score)")
                .param("id",id).param("owner",owner).param("now",now).param("score",score).update();
        jdbc.sql("UPDATE attempts SET status='COMPLETED',final_score_minor=:score,version=version+1 WHERE id=:id AND status='WAIT_SELF_GRADE'")
                .param("id",id).param("score",score).update();
    }
    public String gradingSource(UUID id) {
        return jdbc.sql("SELECT source FROM gradings WHERE attempt_id=:id").param("id",id).query(String.class).optional().orElse("AUTO");
    }
    public List<UUID> mistakes(UUID owner, UUID subject, int size, long offset) {
        return jdbc.sql("""
                SELECT m.question_id FROM mistakes m JOIN question_versions q ON q.question_id=m.question_id AND q.version=m.question_version
                WHERE m.owner_id=:owner AND (CAST(:subject AS uuid) IS NULL OR q.subject_id=CAST(:subject AS uuid))
                ORDER BY m.last_error_at DESC,m.question_id LIMIT :size OFFSET :offset
                """).param("owner",owner).param("subject",subject).param("size",size).param("offset",offset).query(UUID.class).list();
    }
    public long mistakeCount(UUID owner, UUID subject) {
        return jdbc.sql("SELECT count(*) FROM mistakes m JOIN question_versions q ON q.question_id=m.question_id AND q.version=m.question_version WHERE m.owner_id=:owner "
                + "AND (CAST(:subject AS uuid) IS NULL OR q.subject_id=CAST(:subject AS uuid))").param("owner",owner).param("subject",subject).query(Long.class).single();
    }
    public Optional<UUID> mistakeAttempt(UUID owner, UUID question) {
        return jdbc.sql("SELECT attempt_id FROM mistakes WHERE owner_id=:owner AND question_id=:question")
                .param("owner",owner).param("question",question).query(UUID.class).optional();
    }
    private JsonNode decode(String value) {
        if (value == null) return null;
        try { return json.readTree(value); } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
    }
}
