package com.onlineexam.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onlineexam.platform.domain.ApiException;
import com.onlineexam.platform.domain.Identity.Role;
import com.onlineexam.platform.domain.Identity.Status;
import com.onlineexam.platform.domain.Paper;
import com.onlineexam.platform.domain.Paper.Type;
import com.onlineexam.platform.repository.AccountRepository;
import com.onlineexam.platform.repository.AttemptRepository;
import com.onlineexam.platform.repository.AttemptRepository.Attempt;
import com.onlineexam.platform.repository.AttemptRepository.SavedAnswer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttemptService {
    public record QuestionView(int position, int groupPosition, String groupName, UUID questionId,
                               int questionVersion, String score, String type, String stemHtml, JsonNode options) {}
    public record View(UUID id, UUID paperId, UUID paperVersionId, String status,
                       OffsetDateTime serverNow, OffsetDateTime deadlineAt, int version,
                       List<QuestionView> questions, List<SavedAnswer> savedAnswers) {}

    private final AttemptRepository attempts;
    private final AccountRepository accounts;
    private final Clock clock;

    public AttemptService(AttemptRepository attempts, AccountRepository accounts, Clock clock) {
        this.attempts = attempts;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional
    public View start(UUID owner, UUID paper, String key) {
        String normalizedKey = validateKey(key);
        var account = student(owner);

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (normalizedKey != null) {
            var previous = attempts.startKey(owner, normalizedKey);
            if (previous.isPresent()) {
                var record = previous.get();
                if (now.isBefore(record.expiresAt())) {
                    if (!record.paperId().equals(paper)) throw ApiException.conflict("幂等键已用于其他试卷。");
                    return view(owner, record.attemptId(), now);
                }
                attempts.deleteKey(owner, normalizedKey);
            }
        }

        var eligible = attempts.eligible(paper, account.gradeId()).orElseThrow(AttemptService::missingPaper);
        now = OffsetDateTime.now(clock);
        if (eligible.type() == Type.TIMED && (now.isBefore(eligible.opensAt())
                || !now.isBefore(eligible.closesAt()))) {
            throw new ApiException(HttpStatus.CONFLICT, "PAPER_NOT_OPEN", "试卷当前不在开放时段。");
        }
        OffsetDateTime deadline = now.plusMinutes(eligible.durationMinutes());
        if (eligible.type() == Type.TIMED && eligible.closesAt().isBefore(deadline)) {
            deadline = eligible.closesAt();
        }
        UUID id = UUID.randomUUID();
        attempts.insert(id, owner, paper, eligible.versionId(), now, deadline);
        if (attempts.snapshotItems(id, eligible.versionId()) == 0) {
            throw ApiException.conflict("已发布试卷没有可作答题目。");
        }
        if (normalizedKey != null) attempts.insertKey(owner, normalizedKey, paper, id, now.plusHours(24));
        return view(owner, id, now);
    }

    @Transactional
    public View startTask(UUID owner, UUID task, UUID paper, String key) {
        String normalizedKey = validateKey(key);
        student(owner);
        OffsetDateTime now = OffsetDateTime.now(clock);
        var existing = attempts.taskAttempt(owner, task, paper);
        if (normalizedKey != null) {
            var previous = attempts.startKey(owner, normalizedKey);
            if (previous.isPresent()) {
                var record = previous.get();
                if (now.isBefore(record.expiresAt())) {
                    if (!record.paperId().equals(paper)) throw ApiException.conflict("幂等键已用于其他试卷。");
                    if (existing.isEmpty() || !existing.get().id().equals(record.attemptId())) throw missingPaper();
                    return view(owner, existing.get().id(), now);
                }
                attempts.deleteKey(owner, normalizedKey);
            }
        }
        if (existing.isPresent()) {
            if (normalizedKey != null) attempts.insertKey(owner, normalizedKey, paper, existing.get().id(), now.plusHours(24));
            return view(owner, existing.get().id(), now);
        }
        var eligible = attempts.eligibleTaskPaper(owner, task, paper).orElseThrow(AttemptService::missingPaper);
        now = OffsetDateTime.now(clock);
        UUID id = UUID.randomUUID();
        if (!attempts.insertTaskIfAbsent(id, owner, task, paper, eligible.versionId(), now,
                now.plusMinutes(eligible.durationMinutes()))) {
            var winner = attempts.taskAttempt(owner, task, paper).orElseThrow(AttemptService::missingAttempt);
            if (normalizedKey != null) attempts.insertKey(owner, normalizedKey, paper, winner.id(), now.plusHours(24));
            return view(owner, winner.id(), now);
        }
        if (attempts.snapshotItems(id, eligible.versionId()) == 0) {
            throw ApiException.conflict("已发布试卷没有可作答题目。");
        }
        if (normalizedKey != null) attempts.insertKey(owner, normalizedKey, paper, id, now.plusHours(24));
        return view(owner, id, now);
    }

    @Transactional(readOnly = true)
    public View read(UUID owner, UUID id) {
        return view(owner, id, OffsetDateTime.now(clock));
    }

    @Transactional
    public View save(UUID owner, UUID id, JsonNode body) {
        student(owner);
        if (body == null || !body.isObject() || !fields(body, Set.of("expectedVersion", "answerItems"))
                || !body.path("expectedVersion").isIntegralNumber()
                || !body.path("answerItems").isArray()) throw ApiException.validation("保存载荷格式无效。");
        int expected = body.path("expectedVersion").asInt();
        if (body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2 * 1024 * 1024) {
            throw ApiException.validation("整份答案不得超过 2 MiB。");
        }
        Attempt attempt = attempts.owned(owner, id, true).orElseThrow(AttemptService::missingAttempt);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!"IN_PROGRESS".equals(attempt.status()) || !now.isBefore(attempt.deadlineAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "EXAM_CLOSED", "答卷已截止或不可编辑。");
        }
        if (expected != attempt.version()) throw ApiException.conflict("答卷已在其他客户端更新，请加载服务端版本。");
        var questions = attempts.questions(id);
        var byId = new java.util.HashMap<UUID, AttemptRepository.Question>();
        for (var question : questions) byId.put(question.questionId(), question);
        var seen = new HashSet<UUID>();
        var saved = new ArrayList<SavedAnswer>();
        for (JsonNode item : body.path("answerItems")) {
            if (!item.isObject() || !fields(item, Set.of("questionId", "answer"))
                    || !item.path("questionId").isTextual()) throw ApiException.validation("答案条目格式无效。");
            UUID questionId;
            try { questionId = UUID.fromString(item.path("questionId").asText()); }
            catch (IllegalArgumentException error) { throw ApiException.validation("题目标识无效。"); }
            var question = byId.get(questionId);
            if (question == null || !seen.add(questionId)) throw ApiException.validation("答案包含卷外或重复题目。");
            JsonNode answer = item.get("answer");
            if (answer == null) throw ApiException.validation("答案必须显式提供；清空请使用 null。");
            if (!answer.isNull()) {
                validateAnswer(question, answer);
                saved.add(new SavedAnswer(questionId, answer));
            }
        }
        attempts.replaceAnswers(id, saved);
        return view(owner, id, now);
    }

    private static boolean fields(JsonNode node, Set<String> allowed) {
        var names = node.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) return false;
        return true;
    }

    private static void validateAnswer(AttemptRepository.Question question, JsonNode answer) {
        if (!answer.isObject()) throw ApiException.validation("答案结构与题型不匹配。");
        switch (question.type()) {
            case "SINGLE", "TRUE_FALSE" -> {
                if (!fields(answer, Set.of("optionId")) || !answer.path("optionId").isTextual())
                    throw ApiException.validation("单选或判断答案无效。");
                String option = answer.path("optionId").asText();
                if ("TRUE_FALSE".equals(question.type()) ? !Set.of("true", "false").contains(option)
                        : !optionIds(question).contains(option)) throw ApiException.validation("选项不属于答卷快照。");
            }
            case "MULTIPLE" -> {
                JsonNode values = answer.path("optionIds");
                if (!fields(answer, Set.of("optionIds")) || !values.isArray() || values.isEmpty()
                        || values.size() > 10) throw ApiException.validation("多选答案无效。");
                var seen = new HashSet<String>();
                for (JsonNode value : values) if (!value.isTextual() || !optionIds(question).contains(value.asText())
                        || !seen.add(value.asText())) throw ApiException.validation("多选包含无效或重复选项。");
            }
            case "GAP" -> {
                JsonNode values = answer.path("texts");
                if (!fields(answer, Set.of("texts")) || !values.isArray() || values.size() != question.gapCount())
                    throw ApiException.validation("填空数量与答卷快照不符。");
                for (JsonNode value : values) if (!validText(value, 1000))
                    throw ApiException.validation("填空内容超限或无效。");
            }
            case "SHORT" -> {
                if (!fields(answer, Set.of("text")) || !validText(answer.path("text"), 10000))
                    throw ApiException.validation("简答内容超限或无效。");
            }
            default -> throw ApiException.validation("未知题型。");
        }
    }

    private static boolean validText(JsonNode value, int limit) {
        return value.isTextual() && value.asText().length() <= limit && value.asText().indexOf(0) < 0;
    }

    private static Set<String> optionIds(AttemptRepository.Question question) {
        var ids = new HashSet<String>();
        for (JsonNode option : question.options()) ids.add(option.path("id").asText());
        return ids;
    }

    private View view(UUID owner, UUID id, OffsetDateTime now) {
        Attempt attempt = attempts.owned(owner, id).orElseThrow(AttemptService::missingAttempt);
        var questions = attempts.questions(id).stream().map(item -> new QuestionView(
                item.position(), item.groupPosition(), item.groupName(), item.questionId(),
                item.questionVersion(), Paper.score(item.scoreMinor()), item.type(),
                item.stemHtml(), item.options())).toList();
        return new View(attempt.id(), attempt.paperId(), attempt.versionId(), attempt.status(),
                now, attempt.deadlineAt(), attempt.version(), questions, attempts.answers(id));
    }

    private com.onlineexam.platform.domain.Identity.Account student(UUID owner) {
        var account = accounts.lockAccount(owner).orElseThrow(ApiException::unauthenticated);
        if (account.status() != Status.ENABLED) throw ApiException.unauthenticated();
        if (account.role() != Role.STUDENT) throw ApiException.forbidden();
        return account;
    }

    private static String validateKey(String key) {
        if (key == null) return null;
        if (key.length() < 1 || key.length() > 128 || key.chars().anyMatch(ch -> ch < 33 || ch > 126)) {
            throw ApiException.validation("Idempotency-Key 需要 1–128 个可打印 ASCII 字符。");
        }
        return key;
    }

    private static ApiException missingPaper() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "试卷不可用于开始作答。");
    }

    private static ApiException missingAttempt() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "答卷不存在。");
    }
}
