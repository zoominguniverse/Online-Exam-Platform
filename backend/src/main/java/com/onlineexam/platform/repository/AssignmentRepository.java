package com.onlineexam.platform.repository;

import com.onlineexam.platform.domain.Assignment.Draft;
import com.onlineexam.platform.domain.Assignment.PaperItem;
import com.onlineexam.platform.domain.Assignment.State;
import com.onlineexam.platform.domain.Assignment.Task;
import com.onlineexam.platform.domain.Assignment.StudentPaper;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AssignmentRepository {
    private static final RowMapper<Task> TASK = (row, index) -> {
        var array = row.getArray("selected_papers");
        try {
            var ids = Arrays.stream((Object[]) array.getArray()).map(value -> UUID.fromString(value.toString())).toList();
            return new Task(row.getObject("id", UUID.class), row.getObject("grade_id", UUID.class), row.getString("name"),
                    State.valueOf(row.getString("state")), row.getInt("version"), ids,
                    row.getObject("created_at", OffsetDateTime.class), row.getObject("published_at", OffsetDateTime.class));
        } finally {
            array.free();
        }
    };
    private static final String FILTER = """
            FROM tasks WHERE (CAST(:grade AS uuid) IS NULL OR grade_id = CAST(:grade AS uuid))
            AND (:state IS NULL OR state = :state)
            """;
    private final JdbcClient jdbc;

    public record EligiblePaper(UUID id, UUID versionId, UUID gradeId) {}

    public AssignmentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<Task> get(UUID id, boolean lock) {
        return jdbc.sql("SELECT * FROM tasks WHERE id = :id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(TASK).optional();
    }

    public List<Task> list(UUID grade, State state, int size, long offset) {
        return filter("SELECT * " + FILTER + " ORDER BY created_at DESC, id LIMIT :size OFFSET :offset", grade, state)
                .param("size", size).param("offset", offset).query(TASK).list();
    }

    public long count(UUID grade, State state) {
        return filter("SELECT count(*) " + FILTER, grade, state).query(Long.class).single();
    }

    private JdbcClient.StatementSpec filter(String sql, UUID grade, State state) {
        return jdbc.sql(sql).param("grade", grade).param("state", state == null ? null : state.name(), java.sql.Types.VARCHAR);
    }

    public void create(UUID id, Draft draft) {
        jdbc.sql("""
                INSERT INTO tasks (id, grade_id, name, selected_papers) VALUES (:id, :grade, :name, CAST(:papers AS uuid[]))
                """).param("id", id).param("grade", draft.gradeId()).param("name", draft.name())
                .param("papers", arrayLiteral(draft.paperIds())).update();
    }

    public void update(UUID id, Draft draft) {
        jdbc.sql("""
                UPDATE tasks SET grade_id = :grade, name = :name, selected_papers = CAST(:papers AS uuid[]), version = version + 1
                WHERE id = :id AND state = 'DRAFT'
                """).param("id", id).param("grade", draft.gradeId()).param("name", draft.name())
                .param("papers", arrayLiteral(draft.paperIds())).update();
    }

    public List<EligiblePaper> lockEligiblePapers(List<UUID> paperIds) {
        return jdbc.sql("""
                SELECT p.id, p.published_id, s.grade_id
                FROM papers p JOIN subjects s ON s.id = p.subject_id
                WHERE p.id IN (:ids) AND p.type = 'TASK' AND p.published_id IS NOT NULL AND NOT p.archived
                ORDER BY p.id FOR UPDATE OF p
                """).param("ids", paperIds).query((row, index) -> new EligiblePaper(
                        row.getObject("id", UUID.class), row.getObject("published_id", UUID.class),
                        row.getObject("grade_id", UUID.class))).list();
    }

    public void freezePaper(UUID task, EligiblePaper paper, int position) {
        jdbc.sql("""
                INSERT INTO task_papers (task_id, paper_id, paper_version_id, position)
                VALUES (:task, :paper, :version, :position)
                """).param("task", task).param("paper", paper.id()).param("version", paper.versionId())
                .param("position", position).update();
    }

    public void freezeRecipients(UUID task, UUID grade) {
        jdbc.sql("""
                INSERT INTO task_recipients (task_id, user_id)
                SELECT :task, id FROM users WHERE grade_id = :grade AND status = 'ENABLED' AND role = 'STUDENT'
                """).param("task", task).param("grade", grade).update();
    }

    public void publish(UUID id, OffsetDateTime now) {
        jdbc.sql("UPDATE tasks SET state = 'PUBLISHED', version = version + 1, published_at = :now WHERE id = :id")
                .param("id", id).param("now", now).update();
    }

    public void delete(Task task) {
        if (task.state() == State.DRAFT) jdbc.sql("DELETE FROM tasks WHERE id = :id").param("id", task.id()).update();
        else jdbc.sql("UPDATE tasks SET state = 'ARCHIVED', version = version + 1 WHERE id = :id").param("id", task.id()).update();
    }

    public List<PaperItem> papers(UUID task) {
        return jdbc.sql("""
                SELECT tp.paper_id, tp.paper_version_id, tp.position, v.name, v.duration_minutes FROM task_papers tp
                JOIN paper_versions v ON tp.paper_version_id = v.id WHERE tp.task_id = :task ORDER BY tp.position
                """).param("task", task).query((row, index) -> new PaperItem(row.getObject("paper_id", UUID.class),
                        row.getObject("paper_version_id", UUID.class), row.getInt("position"),
                        row.getString("name"), row.getInt("duration_minutes"))).list();
    }

    public long recipients(UUID task) {
        return jdbc.sql("SELECT count(*) FROM task_recipients WHERE task_id = :task")
                .param("task", task).query(Long.class).single();
    }

    public List<StudentPaper> studentPapers(UUID task, UUID user) {
        return jdbc.sql("""
                SELECT tp.paper_id,tp.paper_version_id,tp.position,v.name,v.duration_minutes,a.id,
                       COALESCE(a.status,'NOT_STARTED') AS status FROM task_papers tp
                JOIN paper_versions v ON v.id=tp.paper_version_id
                LEFT JOIN attempts a ON a.task_id=tp.task_id AND a.paper_id=tp.paper_id AND a.owner_id=:user
                WHERE tp.task_id=:task ORDER BY tp.position
                """).param("user",user).param("task",task).query((row,index)->new StudentPaper(
                        row.getObject("paper_id",UUID.class),row.getObject("paper_version_id",UUID.class),row.getInt("position"),
                        row.getString("name"),row.getInt("duration_minutes"),row.getString("status"),row.getObject("id",UUID.class))).list();
    }

    public List<Task> listForUser(UUID user, int size, long offset) {
        return jdbc.sql("""
                SELECT t.* FROM task_recipients r JOIN tasks t ON t.id = r.task_id
                WHERE r.user_id = :user ORDER BY t.published_at DESC, t.id LIMIT :size OFFSET :offset
                """).param("user", user).param("size", size).param("offset", offset).query(TASK).list();
    }

    public long countForUser(UUID user) {
        return jdbc.sql("SELECT count(*) FROM task_recipients WHERE user_id = :user")
                .param("user", user).query(Long.class).single();
    }

    public Optional<Task> getForUser(UUID task, UUID user) {
        return jdbc.sql("""
                SELECT t.* FROM task_recipients r JOIN tasks t ON t.id = r.task_id
                WHERE r.task_id = :task AND r.user_id = :user
                """).param("task", task).param("user", user).query(TASK).optional();
    }

    private static String arrayLiteral(List<UUID> values) {
        return "{" + String.join(",", values.stream().map(UUID::toString).toList()) + "}";
    }
}
