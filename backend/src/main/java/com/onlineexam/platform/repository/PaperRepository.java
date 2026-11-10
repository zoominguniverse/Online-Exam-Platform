package com.onlineexam.platform.repository;

import com.onlineexam.platform.domain.Paper.Draft;
import com.onlineexam.platform.domain.Paper.Availability;
import com.onlineexam.platform.domain.Paper.Head;
import com.onlineexam.platform.domain.Paper.Item;
import com.onlineexam.platform.domain.Paper.Snapshot;
import com.onlineexam.platform.domain.Paper.Type;
import com.onlineexam.platform.domain.Paper.StudentView;
import com.onlineexam.platform.domain.Paper.Summary;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaperRepository {
    private static final RowMapper<Head> HEAD = (row, index) -> new Head(
            row.getObject("id", UUID.class), row.getObject("subject_id", UUID.class),
            Type.valueOf(row.getString("type")), row.getInt("version"),
            row.getObject("draft_id", UUID.class), row.getObject("published_id", UUID.class),
            row.getBoolean("archived"), row.getObject("created_at", OffsetDateTime.class));
    private static final String FILTER = """
            FROM papers p JOIN paper_versions v ON v.id = COALESCE(p.draft_id, p.published_id)
            JOIN subjects s ON s.id = p.subject_id
            WHERE (CAST(:subject AS uuid) IS NULL OR p.subject_id = CAST(:subject AS uuid))
            AND (CAST(:grade AS uuid) IS NULL OR s.grade_id = CAST(:grade AS uuid))
            AND (:type IS NULL OR p.type = :type) AND (:archived OR NOT p.archived)
            """;
    private final JdbcClient jdbc;
    private final JdbcTemplate batch;
    private static final String VISIBLE = """
            FROM (
                SELECT p.id, p.subject_id, p.type, v.name, v.duration_minutes, v.opens_at, v.closes_at,
                    CASE WHEN p.type = 'FIXED' THEN 'OPEN'
                        WHEN v.opens_at > :now THEN 'UPCOMING' WHEN v.closes_at <= :now THEN 'CLOSED'
                        ELSE 'OPEN' END AS availability
                FROM papers p JOIN paper_versions v ON p.published_id = v.id
                JOIN subjects s ON p.subject_id = s.id JOIN grades g ON s.grade_id = g.id
                WHERE NOT p.archived AND p.type IN ('FIXED', 'TIMED') AND g.enabled AND g.id = :grade
            ) visible
            WHERE (:type IS NULL OR type = :type) AND (:status IS NULL OR availability = :status)
            AND (CAST(:id AS uuid) IS NULL OR id = CAST(:id AS uuid))
            """;

    public record Selection(UUID id, UUID subjectId, int version, int score) {}

    public PaperRepository(JdbcClient jdbc, JdbcTemplate batch) {
        this.jdbc = jdbc;
        this.batch = batch;
    }

    public Optional<Head> head(UUID id, boolean lock) {
        return jdbc.sql("SELECT * FROM papers WHERE id = :id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(HEAD).optional();
    }

    public List<Summary> list(UUID subject, UUID grade, Type type, boolean archived, int size, long offset) {
        return filter("SELECT p.*, v.name " + FILTER + " ORDER BY p.created_at DESC, p.id LIMIT :size OFFSET :offset",
                subject, grade, type, archived).param("size", size).param("offset", offset).query((row, index) ->
                    new Summary(row.getObject("id", UUID.class), row.getObject("subject_id", UUID.class),
                        Type.valueOf(row.getString("type")), row.getString("name"), row.getInt("version"),
                        row.getObject("draft_id", UUID.class), row.getObject("published_id", UUID.class),
                        row.getBoolean("archived"))).list();
    }

    public long count(UUID subject, UUID grade, Type type, boolean archived) {
        return filter("SELECT count(*) " + FILTER, subject, grade, type, archived).query(Long.class).single();
    }

    public List<StudentView> visible(UUID grade, Type type, Availability status, UUID id, OffsetDateTime now,
                                     int size, long offset) {
        return visibleQuery("SELECT * " + VISIBLE + " ORDER BY name, id LIMIT :size OFFSET :offset",
                grade, type, status, id, now).param("size", size).param("offset", offset).query((row, index) ->
                        new StudentView(row.getObject("id", UUID.class), row.getObject("subject_id", UUID.class),
                            Type.valueOf(row.getString("type")), row.getString("name"), row.getInt("duration_minutes"),
                            row.getObject("opens_at", OffsetDateTime.class), row.getObject("closes_at", OffsetDateTime.class),
                            Availability.valueOf(row.getString("availability")), now)).list();
    }

    public long visibleCount(UUID grade, Type type, Availability status, OffsetDateTime now) {
        return visibleQuery("SELECT count(*) " + VISIBLE, grade, type, status, null, now).query(Long.class).single();
    }

    private JdbcClient.StatementSpec visibleQuery(String sql, UUID grade, Type type, Availability status,
                                                  UUID id, OffsetDateTime now) {
        return jdbc.sql(sql).param("grade", grade).param("id", id).param("now", now)
                .param("type", type == null ? null : type.name(), java.sql.Types.VARCHAR)
                .param("status", status == null ? null : status.name(), java.sql.Types.VARCHAR);
    }

    private JdbcClient.StatementSpec filter(String sql, UUID subject, UUID grade, Type type, boolean archived) {
        return jdbc.sql(sql).param("subject", subject).param("grade", grade)
                .param("type", type == null ? null : type.name(), java.sql.Types.VARCHAR).param("archived", archived);
    }

    public Optional<Snapshot> snapshot(UUID paper, UUID id) {
        return jdbc.sql("SELECT * FROM paper_versions WHERE paper_id = :paper AND id = :id")
                .param("paper", paper).param("id", id).query((row, index) -> new Snapshot(
                        row.getObject("id", UUID.class), row.getInt("revision"), row.getString("name"),
                        row.getInt("duration_minutes"), row.getObject("opens_at", OffsetDateTime.class),
                        row.getObject("closes_at", OffsetDateTime.class),
                        row.getObject("published_at", OffsetDateTime.class))).optional();
    }

    public List<Item> items(UUID version) {
        return jdbc.sql("SELECT * FROM paper_items WHERE paper_version_id = :version ORDER BY position")
                .param("version", version).query((row, index) -> new Item(row.getInt("position"),
                        row.getInt("group_position"), row.getString("group_name"),
                        row.getObject("question_id", UUID.class), row.getInt("question_version"),
                        row.getInt("score_minor"))).list();
    }

    public List<Selection> lockSelectable(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT q.id, q.subject_id, q.current_version, v.score_minor
                FROM questions q JOIN question_versions v ON v.question_id = q.id AND v.version = q.current_version
                WHERE q.id IN (:ids) AND NOT q.archived ORDER BY q.id FOR SHARE OF q
                """).param("ids", ids).query((row, index) -> new Selection(row.getObject("id", UUID.class),
                        row.getObject("subject_id", UUID.class), row.getInt("current_version"),
                        row.getInt("score_minor"))).list();
    }

    public void insertHead(UUID id, UUID subject, Type type, UUID draft) {
        jdbc.sql("INSERT INTO papers (id, subject_id, type, draft_id) VALUES (:id, :subject, :type, :draft)")
                .param("id", id).param("subject", subject).param("type", type.name()).param("draft", draft).update();
    }

    public void insertDraft(UUID id, UUID paper, Draft draft) {
        jdbc.sql("""
                INSERT INTO paper_versions (id, paper_id, revision, name, duration_minutes, opens_at, closes_at)
                SELECT :id, :paper, COALESCE(MAX(revision), 0) + 1, :name, :duration, :opens, :closes
                FROM paper_versions WHERE paper_id = :paper
                """).param("id", id).param("paper", paper).param("name", draft.name())
                .param("duration", draft.durationMinutes()).param("opens", draft.opensAt())
                .param("closes", draft.closesAt()).update();
    }

    public void replaceDraft(UUID id, Draft draft) {
        jdbc.sql("""
                UPDATE paper_versions SET name = :name, duration_minutes = :duration, opens_at = :opens,
                    closes_at = :closes WHERE id = :id AND published_at IS NULL
                """).param("id", id).param("name", draft.name()).param("duration", draft.durationMinutes())
                .param("opens", draft.opensAt()).param("closes", draft.closesAt()).update();
    }

    public void pointDraft(UUID paper, UUID draft) {
        jdbc.sql("UPDATE papers SET draft_id = :draft, version = version + 1 WHERE id = :id")
                .param("draft", draft).param("id", paper).update();
    }

    public void saveItems(UUID version, List<Item> items) {
        jdbc.sql("DELETE FROM paper_items WHERE paper_version_id = :version").param("version", version).update();
        if (items.isEmpty()) return;
        batch.batchUpdate("""
                INSERT INTO paper_items (paper_version_id, position, group_position, group_name, question_id,
                    question_version, score_minor) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, items.stream().map(item -> new Object[] {version, item.position(), item.groupPosition(),
                    item.groupName(), item.questionId(), item.questionVersion(), item.scoreMinor()}).toList());
    }

    public void publish(UUID id, UUID draft, OffsetDateTime now) {
        jdbc.sql("UPDATE paper_versions SET published_at = :now WHERE id = :draft AND published_at IS NULL")
                .param("draft", draft).param("now", now).update();
        jdbc.sql("UPDATE papers SET published_id = :draft, draft_id = NULL, version = version + 1 WHERE id = :id")
                .param("id", id).param("draft", draft).update();
    }

    public void delete(Head head) {
        if (head.publishedId() == null) {
            jdbc.sql("DELETE FROM papers WHERE id = :id").param("id", head.id()).update();
        } else {
            jdbc.sql("UPDATE papers SET archived = true, version = version + 1 WHERE id = :id")
                    .param("id", head.id()).update();
        }
    }
}
