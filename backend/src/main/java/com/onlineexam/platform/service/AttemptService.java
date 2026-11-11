package com.onlineexam.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onlineexam.platform.domain.ApiException;
import com.onlineexam.platform.domain.ExamClosedException;
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
                       List<QuestionView> questions, List<SavedAnswer> savedAnswers,
                       OffsetDateTime frozenAt, String freezeReason, String automaticScore, String finalScore,
                       List<ScoreView> questionScores) {}
    public record ScoreView(UUID questionId, String score) {}

    private final AttemptRepository attempts;
    private final AccountRepository accounts;
    private final Clock clock;
    private final AttemptLifecycle lifecycle;

    public AttemptService(AttemptRepository attempts, AccountRepository accounts, Clock clock, AttemptLifecycle lifecycle) {
        this.attempts = attempts;
        this.accounts = accounts;
        this.clock = clock;
        this.lifecycle = lifecycle;
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

    @Transactional
    public View read(UUID owner, UUID id) {
        student(owner);
        return view(owner, id, OffsetDateTime.now(clock));
    }

    @Transactional(noRollbackFor = ExamClosedException.class)
    public View save(UUID owner, UUID id, JsonNode body) {
        student(owner);
        validatePayload(body);
        int expected = body.path("expectedVersion").asInt();
        Attempt attempt = attempts.owned(owner, id, true).orElseThrow(AttemptService::missingAttempt);
        OffsetDateTime now = OffsetDateTime.now(clock);
        lifecycle.freezeIfDue(attempt, now);
        if (!"IN_PROGRESS".equals(attempt.status()) || !now.isBefore(attempt.deadlineAt())) {
            throw new ExamClosedException();
        }
        if (expected != attempt.version()) throw ApiException.conflict("答卷已在其他客户端更新，请加载服务端版本。");
        attempts.replaceAnswers(id, validatedAnswers(id, body));
        return view(owner, id, now);
    }

    @Transactional
    public View submit(UUID owner, UUID id, String key, JsonNode body) {
        student(owner);
        String normalized = validateKey(key);
        if (normalized == null) throw ApiException.validation("交卷需要 Idempotency-Key。");
        validatePayload(body);
        String hash = IdentityService.digest(canonical(body));
        Attempt attempt = attempts.owned(owner, id, true).orElseThrow(AttemptService::missingAttempt);
        OffsetDateTime now = OffsetDateTime.now(clock);
        var previous = attempts.submitKey(owner, normalized);
        if (previous.isPresent()) {
            var record = previous.get();
            if (now.isBefore(record.expiresAt())) {
                if (!record.attemptId().equals(id) || !record.requestHash().equals(hash)) {
                    throw ApiException.conflict("交卷幂等键已用于不同载荷或答卷。");
                }
                return view(owner, id, now);
            }
            attempts.deleteSubmitKey(owner, normalized);
        }
        if ("IN_PROGRESS".equals(attempt.status()) && !lifecycle.freezeIfDue(attempt, now)) {
            if (body.path("expectedVersion").asInt() != attempt.version()) {
                throw ApiException.conflict("答卷已在其他客户端更新，请加载服务端版本。");
            }
            attempts.replaceAnswers(id, validatedAnswers(id, body), false);
            lifecycle.freeze(attempt, now, "MANUAL");
        }
        // A terminal answer set wins over every later payload, including an expired-key retry.
        attempts.insertSubmitKey(owner, normalized, id, hash, now);
        return view(owner, id, now);
    }

    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            var fields = new java.util.TreeMap<String, String>();
            node.fields().forEachRemaining(field -> fields.put(field.getKey(), canonical(field.getValue())));
            return "{" + fields.entrySet().stream().map(field ->
                com.fasterxml.jackson.databind.node.TextNode.valueOf(field.getKey()).toString()
                    + ":" + field.getValue()).collect(java.util.stream.Collectors.joining(",")) + "}";
        }
        if (node.isArray()) {
            var items = new ArrayList<String>();
            node.forEach(item -> items.add(canonical(item)));
            return "[" + String.join(",", items) + "]";
        }
        return node.toString();
    }

    private static void validatePayload(JsonNode body) {
        if (body == null || !body.isObject() || !fields(body, Set.of("expectedVersion", "answerItems"))
                || !body.path("expectedVersion").isIntegralNumber() || !body.path("expectedVersion").canConvertToInt()
                || body.path("expectedVersion").asInt() < 1
                || !body.path("answerItems").isArray()) throw ApiException.validation("保存载荷格式无效。");
        if (body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2 * 1024 * 1024) {
            throw ApiException.validation("整份答案不得超过 2 MiB。");
        }
    }

    private List<SavedAnswer> validatedAnswers(UUID id, JsonNode body) {
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
        return saved;
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

    View view(UUID owner, UUID id, OffsetDateTime now) {
        Attempt attempt = attempts.owned(owner, id, true).orElseThrow(AttemptService::missingAttempt);
        now = OffsetDateTime.now(clock);
        if (lifecycle.freezeIfDue(attempt, now)) attempt = attempts.owned(owner, id).orElseThrow();
        var questions = attempts.questions(id).stream().map(item -> new QuestionView(
                item.position(), item.groupPosition(), item.groupName(), item.questionId(),
                item.questionVersion(), Paper.score(item.scoreMinor()), item.type(),
                item.stemHtml(), item.options())).toList();
        return new View(attempt.id(), attempt.paperId(), attempt.versionId(), attempt.status(),
                now, attempt.deadlineAt(), attempt.version(), questions, attempts.answers(id),
                attempt.frozenAt(), attempt.freezeReason(),
                attempt.automaticScoreMinor() == null ? null : Paper.score(attempt.automaticScoreMinor()),
                attempt.finalScoreMinor() == null ? null : Paper.score(attempt.finalScoreMinor()),
                attempts.results(id).stream().map(result -> new ScoreView(result.questionId(),
                    result.scoreMinor() == null ? null : Paper.score(result.scoreMinor()))).toList());
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
