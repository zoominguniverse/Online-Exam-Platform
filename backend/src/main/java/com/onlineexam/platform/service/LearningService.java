package com.onlineexam.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onlineexam.platform.domain.*;
import com.onlineexam.platform.domain.Identity.Role;
import com.onlineexam.platform.domain.Identity.Status;
import com.onlineexam.platform.domain.QuestionBank.Page;
import com.onlineexam.platform.repository.*;
import com.onlineexam.platform.repository.LearningRepository.ReviewItem;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningService {
    public record Review(AttemptService.View attempt, List<ReviewItem> items, String gradingSource,LearningRepository.Metadata metadata) {}
    public record Mistake(UUID questionId, UUID attemptId, ReviewItem item,LearningRepository.SubjectInfo subject) {}
    private final AccountRepository accounts;
    private final AttemptRepository attempts;
    private final AttemptService taking;
    private final LearningRepository learning;
    private final Clock clock;
    public LearningService(AccountRepository accounts, AttemptRepository attempts, AttemptService taking,
                           LearningRepository learning, Clock clock) {
        this.accounts=accounts; this.attempts=attempts; this.taking=taking; this.learning=learning; this.clock=clock;
    }
    private void student(UUID owner) {
        var account=accounts.lockAccount(owner).orElseThrow(ApiException::unauthenticated);
        if(account.status()!=Status.ENABLED) throw ApiException.unauthenticated();
        if(account.role()!=Role.STUDENT) throw ApiException.forbidden();
    }
    @Transactional
    public Review review(UUID owner, UUID id) {
        var view=taking.read(owner,id);
        if("IN_PROGRESS".equals(view.status())) throw ApiException.conflict("提交后才可查看参考答案。");
        return new Review(view,learning.review(id),learning.gradingSource(id),learning.metadata(id));
    }
    @Transactional
    public Review adminReview(UUID id) {
        UUID owner=learning.owner(id).orElseThrow(LearningService::missing);
        accounts.lockAccount(owner).orElseThrow(LearningService::missing);
        return new Review(taking.view(owner,id,OffsetDateTime.now(clock)),learning.review(id),learning.gradingSource(id),learning.metadata(id));
    }
    @Transactional
    public Review confirm(UUID owner, UUID id, String key, JsonNode body) {
        student(owner); Contracts.key(key);
        Contracts.fields(body,Set.of("expectedVersion","scores"),Set.of("expectedVersion","scores"));
        int expected=Contracts.version(body);
        if(!body.path("scores").isArray()) throw ApiException.validation("scores 必须是数组。");
        var attempt=attempts.owned(owner,id,true).orElseThrow(LearningService::missing);
        OffsetDateTime now=OffsetDateTime.now(clock);
        String hash=Contracts.hash(body);
        var previous=learning.key(owner,"SELF_GRADE",key);
        if(previous.isPresent()) {
            var old=previous.get();
            if(now.isBefore(old.expiresAt())) {
                if(!old.targetId().equals(id)||!old.hash().equals(hash)) throw ApiException.conflict("批改幂等键已用于不同载荷或答卷。");
                return review(owner,id);
            }
            learning.deleteKey(owner,"SELF_GRADE",key);
        }
        if("IN_PROGRESS".equals(attempt.status())) throw ApiException.conflict("请先交卷，再自行批改。");
            var questions=learning.review(id);
            var subjective=new HashMap<UUID,ReviewItem>();
            questions.stream().filter(ReviewItem::subjective).forEach(item->subjective.put(item.questionId(),item));
            var scores=new HashMap<UUID,Integer>();
            for(var item:body.path("scores")) {
                Contracts.fields(item,Set.of("questionId","score"),Set.of("questionId","score"));
                UUID question=Contracts.uuid(item.get("questionId"));
                var snapshot=subjective.get(question);
                String value=Contracts.text(item,"score",1,16);
                if(snapshot==null||scores.containsKey(question)||!value.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,2})?"))
                    throw ApiException.validation("只可一次批改全部主观题，分数须为至多两位小数。");
                BigDecimal score=new BigDecimal(value);
                if(score.compareTo(new BigDecimal(snapshot.score()))>0 || (snapshot.empty()&&score.signum()!=0))
                    throw ApiException.validation("主观题分数超出满分，或空答案未固定为零分。");
                scores.put(question,score.movePointRight(2).intValueExact());
            }
            if(!scores.keySet().equals(subjective.keySet())) throw ApiException.validation("须一次提交全部主观题分数，包括空答案零分。");
        if(!"COMPLETED".equals(attempt.status())) {
            if(attempt.version()!=expected) throw ApiException.conflict("答卷版本已变化，请重新加载。");
            for(var score:scores.entrySet()) learning.score(id,score.getKey(),score.getValue());
            int total=Math.addExact(attempt.automaticScoreMinor(),scores.values().stream().mapToInt(Integer::intValue).sum());
            learning.complete(id,owner,total,now);
            attempts.recordMistakes(id,now);
        } else {
            for(var score:scores.entrySet()) {
                String awarded=subjective.get(score.getKey()).awardedScore();
                if(awarded==null||new BigDecimal(awarded).movePointRight(2).intValueExact()!=score.getValue())
                    throw ApiException.conflict("成绩已确认，不能修改；重复确认只能读取原结果。");
            }
        }
        learning.putKey(owner,"SELF_GRADE",key,id,hash,now);
        return review(owner,id);
    }
    @Transactional(readOnly=true)
    public Page<LearningRepository.Summary> list(UUID owner, UUID subject, String status, int page, int size, String sort) {
        SubjectService.checkPage(page,size);
        if(!"startedAt".equals(sort)|| (status!=null&&!Set.of("IN_PROGRESS","WAIT_SELF_GRADE","COMPLETED").contains(status)))
            throw ApiException.validation("筛选或排序字段无效。");
        return new Page<>(learning.list(owner,subject,status,size,(long)(page-1)*size),learning.count(owner,subject,status),page,size);
    }
    @Transactional(readOnly=true)
    public Page<Mistake> mistakes(UUID owner, UUID subject, int page, int size) {
        SubjectService.checkPage(page,size);
        return new Page<>(learning.mistakes(owner,subject,size,(long)(page-1)*size).stream().map(question->mistake(owner,question)).toList(),
                learning.mistakeCount(owner,subject),page,size);
    }
    public Mistake mistake(UUID owner, UUID question) {
        UUID id=learning.mistakeAttempt(owner,question).orElseThrow(LearningService::missing);
        return new Mistake(question,id,learning.review(id).stream().filter(item->item.questionId().equals(question)).findFirst().orElseThrow(),learning.mistakeSubject(id,question));
    }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","答卷或错题不存在。"); }
}
