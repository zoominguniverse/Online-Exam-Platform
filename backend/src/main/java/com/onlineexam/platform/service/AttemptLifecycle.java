package com.onlineexam.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onlineexam.platform.repository.AttemptRepository;
import com.onlineexam.platform.repository.CommunityRepository;
import com.onlineexam.platform.repository.AttemptRepository.Attempt;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AttemptLifecycle {
    private final AttemptRepository attempts;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final CommunityRepository audit;

    public AttemptLifecycle(AttemptRepository attempts, Clock clock, TransactionTemplate transactions,CommunityRepository audit) {
        this.attempts = attempts;
        this.clock = clock;
        this.transactions = transactions;
        this.audit=audit;
    }

    // Caller holds the attempt row lock within the same transaction.
    public boolean freezeIfDue(Attempt attempt, OffsetDateTime now) {
        if (!"IN_PROGRESS".equals(attempt.status()) || now.isBefore(attempt.deadlineAt())) return false;
        freeze(attempt, now, "DEADLINE");
        return true;
    }

    public void freeze(Attempt attempt, OffsetDateTime now, String reason) {
        if (!"IN_PROGRESS".equals(attempt.status())) return;
        boolean subjective = false;
        int total = 0;
        for (var question : attempts.gradingQuestions(attempt.id())) {
            boolean pending = Set.of("GAP", "SHORT").contains(question.type());
            subjective |= pending;
            Integer score = pending ? null : correct(question.type(), question.correctAnswer(), question.answer())
                    ? question.scoreMinor() : 0;
            if (score != null) total = Math.addExact(total, score);
            attempts.insertResult(attempt.id(), question.questionId(), score);
        }
        attempts.freeze(attempt.id(), now, reason, subjective, total);
        if (!subjective) attempts.recordMistakes(attempt.id(), now);
        audit.event(attempts.owner(attempt.id()),com.onlineexam.platform.configuration.RequestContext.id(),"Attempt","Attempt.freeze",attempt.id(),reason,now);
    }

    public int freezeDue() {
        int count = 0;
        for (UUID id : attempts.due(OffsetDateTime.now(clock))) {
            Boolean frozen = transactions.execute(transaction -> {
                // Match HTTP's account -> attempt lock order, including foreign-key checks on mistakes.
                var owner = attempts.lockOwnerForDeadline(id);
                if (owner.isEmpty()) return false;
                if (attempts.lockDue(id).isEmpty()) return false;
                // Sample after acquiring the lock, never the transaction-start timestamp.
                OffsetDateTime now = OffsetDateTime.now(clock);
                return freezeIfDue(attempts.owned(owner.get(), id).orElseThrow(), now);
            });
            if (Boolean.TRUE.equals(frozen)) count++;
        }
        return count;
    }

    private static boolean correct(String type, JsonNode expected, JsonNode actual) {
        if (actual == null) return false;
        if (!"MULTIPLE".equals(type)) return expected.path("optionIds").get(0).asText().equals(actual.path("optionId").asText());
        var selected = new HashSet<String>();
        actual.path("optionIds").forEach(item -> selected.add(item.asText()));
        var reference = new HashSet<String>();
        expected.path("optionIds").forEach(item -> reference.add(item.asText()));
        return reference.equals(selected);
    }
}
