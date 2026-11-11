package com.onlineexam.platform.service;

import com.onlineexam.platform.domain.ApiException;
import com.onlineexam.platform.domain.Assignment.Draft;
import com.onlineexam.platform.domain.Assignment.State;
import com.onlineexam.platform.domain.Assignment.StudentPaper;
import com.onlineexam.platform.domain.Assignment.StudentView;
import com.onlineexam.platform.domain.Assignment.Task;
import com.onlineexam.platform.domain.Assignment.View;
import com.onlineexam.platform.domain.Identity.Role;
import com.onlineexam.platform.domain.QuestionBank.Page;
import com.onlineexam.platform.repository.AccountRepository;
import com.onlineexam.platform.repository.AssignmentRepository;
import com.onlineexam.platform.repository.AssignmentRepository.EligiblePaper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssignmentService {
    private final AssignmentRepository tasks;
    private final AccountRepository accounts;
    private final IdentityService identity;
    private final Clock clock;

    public AssignmentService(AssignmentRepository tasks, AccountRepository accounts, IdentityService identity, Clock clock) {
        this.tasks = tasks;
        this.accounts = accounts;
        this.identity = identity;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<Task> list(UUID grade, State state, int page, int size) {
        SubjectService.checkPage(page, size);
        return new Page<>(tasks.list(grade, state, size, (long) (page - 1) * size), tasks.count(grade, state), page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View detail(UUID id) {
        return new View(get(id), tasks.papers(id), tasks.recipients(id));
    }

    @Transactional
    public Task create(Draft input) {
        Draft draft = validate(input);
        UUID id = UUID.randomUUID();
        tasks.create(id, draft);
        return get(id);
    }

    @Transactional
    public Task edit(UUID id, int expected, Draft input) {
        Task task = lock(id, expected);
        requireDraft(task);
        tasks.update(id, validate(input));
        return get(id);
    }

    @Transactional
    public View publish(UUID id, int expected) {
        Task task = lock(id, expected);
        requireDraft(task);
        requireGrade(task.gradeId());
        if (task.paperIds().isEmpty()) throw ApiException.validation("发布任务前至少选择一张任务卷。");
        var papers = tasks.lockEligiblePapers(task.paperIds());
        if (papers.size() != task.paperIds().size() || papers.stream().anyMatch(paper -> !paper.gradeId().equals(task.gradeId()))) {
            throw ApiException.validation("任务只能包含同年级已发布且未归档的任务卷。");
        }
        var byId = papers.stream().collect(Collectors.toMap(EligiblePaper::id, Function.identity()));
        try {
            for (int position = 0; position < task.paperIds().size(); position++) {
                tasks.freezePaper(id, byId.get(task.paperIds().get(position)), position + 1);
            }
        } catch (DuplicateKeyException error) {
            throw ApiException.conflict("试卷已绑定其他任务。请先创建新的任务卷。");
        }
        tasks.freezeRecipients(id, task.gradeId());
        tasks.publish(id, OffsetDateTime.now(clock));
        return detail(id);
    }

    @Transactional
    public void delete(UUID id, int expected) {
        tasks.delete(lock(id, expected));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<StudentView> listForStudent(UUID user, int page, int size) {
        requireStudent(user);
        SubjectService.checkPage(page, size);
        List<StudentView> items = tasks.listForUser(user, size, (long) (page - 1) * size)
                .stream().map(task -> studentView(task,user)).toList();
        return new Page<>(items, tasks.countForUser(user), page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StudentView detailForStudent(UUID user, UUID id) {
        requireStudent(user);
        return studentView(tasks.getForUser(id, user).orElseThrow(AssignmentService::missing),user);
    }

    private StudentView studentView(Task task, UUID user) {
        var papers = tasks.studentPapers(task.id(),user);
        long completed=papers.stream().filter(paper->"COMPLETED".equals(paper.status())).count();
        return new StudentView(task.id(), task.name(), task.state(), papers,completed,papers.size(),!papers.isEmpty()&&completed==papers.size());
    }

    private Task get(UUID id) {
        return tasks.get(id, false).orElseThrow(AssignmentService::missing);
    }

    private Task lock(UUID id, int version) {
        SubjectService.requireVersion(version);
        Task task = tasks.get(id, true).filter(item -> item.state() != State.ARCHIVED)
                .orElseThrow(AssignmentService::missing);
        if (task.version() != version) throw ApiException.conflict("任务已更新，请刷新后重试。");
        return task;
    }

    private Draft validate(Draft draft) {
        if (draft == null) throw ApiException.validation("任务内容必填。");
        requireGrade(draft.gradeId());
        String name = draft.name() == null ? "" : draft.name().strip();
        if (name.isBlank() || name.codePointCount(0, name.length()) > 100
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.validation("name 需要 1–100 个可显示字符。");
        }
        List<UUID> ids = draft.paperIds() == null ? List.of() : draft.paperIds();
        if (ids.size() > 200 || ids.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(ids).size() != ids.size()) {
            throw ApiException.validation("paperIds 最多包含 200 张不同试卷。");
        }
        return new Draft(draft.gradeId(), name, List.copyOf(ids));
    }

    private void requireGrade(UUID grade) {
        if (grade == null || !accounts.gradeEnabled(grade)) throw ApiException.validation("gradeId 必须是启用年级。");
    }

    private void requireStudent(UUID user) {
        if (identity.current(user).role() != Role.STUDENT) throw ApiException.forbidden();
    }

    private static void requireDraft(Task task) {
        if (task.state() != State.DRAFT) throw ApiException.conflict("已发布任务不可修改，请新建任务。");
    }

    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "任务不存在。");
    }
}
