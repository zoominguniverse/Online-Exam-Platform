package com.onlineexam.platform.service;

import com.onlineexam.platform.domain.ApiException;
import com.onlineexam.platform.domain.Identity.Role;
import com.onlineexam.platform.domain.Paper.Availability;
import com.onlineexam.platform.domain.Paper.Draft;
import com.onlineexam.platform.domain.Paper.Group;
import com.onlineexam.platform.domain.Paper.Head;
import com.onlineexam.platform.domain.Paper.Item;
import com.onlineexam.platform.domain.Paper.StudentView;
import com.onlineexam.platform.domain.Paper.Summary;
import com.onlineexam.platform.domain.Paper.Type;
import com.onlineexam.platform.domain.Paper.View;
import com.onlineexam.platform.domain.QuestionBank.Page;
import com.onlineexam.platform.repository.AccountRepository;
import com.onlineexam.platform.repository.PaperRepository;
import com.onlineexam.platform.repository.PaperRepository.Selection;
import com.onlineexam.platform.repository.SubjectRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaperService {
    private final PaperRepository papers;
    private final SubjectRepository subjects;
    private final AccountRepository accounts;
    private final Clock clock;
    private final IdentityService identity;

    public PaperService(PaperRepository papers, SubjectRepository subjects, AccountRepository accounts,
                        IdentityService identity, Clock clock) {
        this.papers = papers;
        this.subjects = subjects;
        this.accounts = accounts;
        this.clock = clock;
        this.identity = identity;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<Summary> list(UUID subject, UUID grade, Type type, boolean includeArchived, int page, int size) {
        SubjectService.checkPage(page, size);
        return new Page<>(papers.list(subject, grade, type, includeArchived, size, (long) (page - 1) * size),
                papers.count(subject, grade, type, includeArchived), page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View detail(UUID id, UUID selected) {
        return view(papers.head(id, false).orElseThrow(PaperService::missing), selected);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<StudentView> listVisible(UUID user, Type type, Availability status, int page, int size) {
        SubjectService.checkPage(page, size);
        UUID grade = studentGrade(user);
        OffsetDateTime now = OffsetDateTime.now(clock);
        return new Page<>(papers.visible(grade, type, status, null, now, size, (long) (page - 1) * size),
                papers.visibleCount(grade, type, status, now), page, size);
    }

    public StudentView visibleDetail(UUID user, UUID id) {
        return papers.visible(studentGrade(user), null, null, id, OffsetDateTime.now(clock), 1, 0)
                .stream().findFirst().orElseThrow(PaperService::missing);
    }

    private UUID studentGrade(UUID id) {
        var account = identity.current(id);
        if (account.role() != Role.STUDENT) throw ApiException.forbidden();
        return account.gradeId();
    }

    @Transactional
    public View create(UUID subject, Type type, Draft input) {
        if (type == null) throw ApiException.validation("type 必须为 FIXED、TIMED 或 TASK。");
        validateSubject(subject);
        Draft draft = normalize(input, type);
        List<Item> items = selectItems(subject, draft.groups());
        UUID id = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        papers.insertHead(id, subject, type, draftId);
        papers.insertDraft(draftId, id, draft);
        papers.saveItems(draftId, items);
        return current(id);
    }

    @Transactional
    public View edit(UUID id, int expected, Draft input) {
        Head head = requireActive(id, expected);
        validateSubject(head.subjectId());
        Draft draft = normalize(input, head.type());
        List<Item> items = selectItems(head.subjectId(), draft.groups());
        UUID draftId = head.draftId();
        if (draftId == null) {
            draftId = UUID.randomUUID();
            papers.insertDraft(draftId, id, draft);
        } else {
            papers.replaceDraft(draftId, draft);
        }
        papers.saveItems(draftId, items);
        papers.pointDraft(id, draftId);
        return current(id);
    }

    @Transactional
    public View publish(UUID id, int expected) {
        Head head = requireActive(id, expected);
        validateSubject(head.subjectId());
        if (head.draftId() == null) throw ApiException.conflict("试卷没有待发布的草稿。");
        var draft = papers.snapshot(id, head.draftId()).orElseThrow(PaperService::missing);
        List<Item> items = papers.items(draft.id());
        if (items.isEmpty() || items.size() > 200) throw ApiException.validation("发布时需要 1–200 道题目。");
        if (head.type() == Type.TIMED && (draft.opensAt() == null || draft.closesAt() == null
                || !draft.closesAt().isAfter(draft.opensAt()))) {
            throw ApiException.validation("时段卷的结束时间必须晚于开始时间。");
        }
        requireSelectable(head.subjectId(), items.stream().map(Item::questionId).toList());
        papers.publish(id, draft.id(), OffsetDateTime.now(clock));
        return current(id);
    }

    @Transactional
    public void delete(UUID id, int expected) {
        papers.delete(requireActive(id, expected));
    }

    private View current(UUID id) {
        return view(papers.head(id, false).orElseThrow(PaperService::missing), null);
    }

    private View view(Head head, UUID selected) {
        UUID version = selected == null ? (head.draftId() == null ? head.publishedId() : head.draftId()) : selected;
        var content = papers.snapshot(head.id(), version).orElseThrow(PaperService::missing);
        return View.of(head, content, papers.items(version));
    }

    private Head requireActive(UUID id, int expected) {
        SubjectService.requireVersion(expected);
        Head head = papers.head(id, true).filter(paper -> !paper.archived()).orElseThrow(PaperService::missing);
        if (head.version() != expected) throw ApiException.conflict("试卷已更新，请刷新后重试。");
        return head;
    }

    private List<Item> selectItems(UUID subject, List<Group> groups) {
        List<UUID> ids = groups.stream().flatMap(group -> group.questionIds().stream()).toList();
        Map<UUID, Selection> available = requireSelectable(subject, ids);
        List<Item> items = new ArrayList<>();
        for (int index = 0; index < groups.size(); index++) {
            Group group = groups.get(index);
            for (UUID id : group.questionIds()) {
                var item = available.get(id);
                items.add(new Item(items.size() + 1, index + 1, group.name(), id, item.version(), item.score()));
            }
        }
        return items;
    }

    private Map<UUID, Selection> requireSelectable(UUID subject, List<UUID> ids) {
        var selected = papers.lockSelectable(ids);
        if (selected.size() != ids.size() || selected.stream().anyMatch(item -> !item.subjectId().equals(subject))) {
            throw ApiException.validation("试卷只能选择同一学科且未归档的题目。");
        }
        return selected.stream().collect(Collectors.toMap(Selection::id, Function.identity()));
    }

    private void validateSubject(UUID id) {
        if (id == null || subjects.get(id).filter(subject -> accounts.gradeEnabled(subject.gradeId())).isEmpty()) {
            throw ApiException.validation("subjectId 必须属于启用年级的学科。");
        }
    }

    private static Draft normalize(Draft input, Type type) {
        if (input == null || input.durationMinutes() < 1 || input.durationMinutes() > 180) {
            throw ApiException.validation("durationMinutes 范围是 1–180 分钟。");
        }
        String name = clean(input.name(), 100, "name");
        if (type != Type.TIMED && (input.opensAt() != null || input.closesAt() != null)) {
            throw ApiException.validation("只有时段卷可设置开放时段。");
        }
        List<Group> groups = input.groups() == null ? List.of() : input.groups();
        List<Group> normalized = new ArrayList<>();
        var unique = new HashSet<UUID>();
        for (Group group : groups) {
            if (group == null || group.questionIds() == null || group.questionIds().isEmpty()) {
                throw ApiException.validation("每个分组至少需要一道题。");
            }
            for (UUID id : group.questionIds()) {
                if (id == null || !unique.add(id)) throw ApiException.validation("分组包含空题目或重复题目。");
                if (unique.size() > 200) throw ApiException.validation("试卷最多包含 200 道题。");
            }
            normalized.add(new Group(clean(group.name(), 80, "group.name"), List.copyOf(group.questionIds())));
        }
        return new Draft(name, input.durationMinutes(), input.opensAt(), input.closesAt(), List.copyOf(normalized));
    }

    private static String clean(String input, int max, String field) {
        String value = input == null ? "" : input.strip();
        if (value.isBlank() || value.codePointCount(0, value.length()) > max
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.validation(field + " 需要 1–" + max + " 个可显示字符。");
        }
        return value;
    }

    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "试卷或指定版本不存在。");
    }
}
