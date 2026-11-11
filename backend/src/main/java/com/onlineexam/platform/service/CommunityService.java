package com.onlineexam.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onlineexam.platform.domain.*;
import com.onlineexam.platform.domain.Identity.Role;
import com.onlineexam.platform.domain.Identity.Status;
import com.onlineexam.platform.domain.QuestionBank.Page;
import com.onlineexam.platform.repository.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunityService {
    public record Sent(CommunityRepository.Message message,List<CommunityRepository.Recipient> recipients) {}
    public record Inbox(UUID id,String title,String body,OffsetDateTime sentAt,OffsetDateTime readAt) {}
    public record DayCount(LocalDate day,long count) {}
    public record Statistics(long paperCount,long questionCount,List<DayCount> activeUsers,List<DayCount> newQuestions,String timeZone) {}
    private final CommunityRepository community;
    private final LearningRepository keys;
    private final AccountRepository accounts;
    private final Clock clock;
    public CommunityService(CommunityRepository community,LearningRepository keys,AccountRepository accounts,Clock clock) {
        this.community=community;this.keys=keys;this.accounts=accounts;this.clock=clock;
    }
    @Transactional
    public CommunityRepository.Profile profile(UUID owner) { enabled(owner);return community.profile(owner); }
    @Transactional
    public CommunityRepository.Profile updateProfile(UUID owner,JsonNode body) {
        var account=enabled(owner);
        Set<String> allowed=account.role()==Role.STUDENT?Set.of("expectedVersion","name","phone","birthday"):Set.of("expectedVersion","name","phone");
        Contracts.fields(body,allowed,Set.of("expectedVersion","name","phone"));
        int version=Contracts.version(body);
        var old=community.profile(owner);
        if(old.version()!=version) throw ApiException.conflict("资料已更新，请重新加载。");
        String name=Contracts.text(body,"name",0,100).strip();
        String phone=Contracts.text(body,"phone",0,32).strip();
        if(name.codePoints().anyMatch(Character::isISOControl)||!phone.matches("[+0-9 ()-]*")) throw ApiException.validation("姓名或手机号格式无效。");
        LocalDate birthday=old.birthday();
        if(body.has("birthday")) {
            try { birthday=body.get("birthday").isNull()?null:LocalDate.parse(Contracts.text(body,"birthday",10,10)); }
            catch(DateTimeException error) { throw ApiException.validation("生日须为有效日期。"); }
            if(birthday!=null&&(birthday.isBefore(LocalDate.of(1900,1,1))||birthday.isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Hong_Kong"))))))
                throw ApiException.validation("生日须在 1900 年至今天之间。");
        }
        community.updateProfile(owner,name,phone,birthday);
        return community.profile(owner);
    }
    @Transactional
    public CommunityRepository.Profile avatar(UUID owner,UUID image) {
        enabled(owner);community.profile(owner);
        if(!community.ownsImage(owner,image)) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","图片不存在。");
        community.avatar(owner,image);return community.profile(owner);
    }
    private com.onlineexam.platform.domain.Identity.Account enabled(UUID owner) {
        var account=accounts.lockAccount(owner).orElseThrow(ApiException::unauthenticated);
        if(account.status()!=Status.ENABLED) throw ApiException.unauthenticated();return account;
    }
    @Transactional
    public Sent send(UUID sender,String key,JsonNode body) {
        accounts.lockAdministration();
        if(enabled(sender).role()!=Role.ADMIN) throw ApiException.forbidden();
        Contracts.key(key);Contracts.fields(body,Set.of("title","body","recipientIds"),Set.of("title","body","recipientIds"));
        String title=Contracts.text(body,"title",1,200).strip();
        String text=Contracts.text(body,"body",1,10000);
        if(title.isBlank()||text.isBlank()||!body.path("recipientIds").isArray()) throw ApiException.validation("标题、正文和收件人必填。");
        var ids=new TreeSet<UUID>();for(var value:body.path("recipientIds")) ids.add(Contracts.uuid(value));
        if(ids.isEmpty()||ids.size()>500) throw ApiException.validation("收件人须为 1–500 个有效用户。");
        var now=OffsetDateTime.now(clock);String hash=Contracts.hash(body);
        var previous=keys.key(sender,"SEND_MESSAGE",key);
        if(previous.isPresent()) {
            var old=previous.get();
            if(now.isBefore(old.expiresAt())) {
                if(!old.hash().equals(hash)) throw ApiException.conflict("发送幂等键已用于不同载荷。");
                return sent(old.targetId());
            }
            keys.deleteKey(sender,"SEND_MESSAGE",key);
        }
        var recipients=community.recipients(ids);
        if(recipients.size()!=ids.size()) throw ApiException.validation("收件人包含不存在、已删除或禁用账号，未发送任何消息。");
        UUID id=UUID.randomUUID();community.send(id,sender,title,text,recipients,now);
        keys.putKey(sender,"SEND_MESSAGE",key,id,hash,now);return sent(id);
    }
    public Sent sent(UUID id) { return new Sent(community.message(id,null).orElseThrow(CommunityService::missing),community.recipients(id)); }
    public Inbox inbox(UUID owner,UUID id) { return inbox(community.message(id,owner).orElseThrow(CommunityService::missing)); }
    private Inbox inbox(CommunityRepository.Message item) { return new Inbox(item.id(),item.title(),item.body(),item.sentAt(),item.readAt()); }
    @Transactional(readOnly=true)
    public Page<?> messages(UUID owner,int page,int size) {
        SubjectService.checkPage(page,size);
        var items=community.messages(owner,size,(long)(page-1)*size);
        if(owner==null) return new Page<>(items,community.messageCount(null),page,size);
        return new Page<>(items.stream().map(this::inbox).toList(),community.messageCount(owner),page,size);
    }
    @Transactional
    public Inbox markRead(UUID owner,UUID id) {
        if(community.markRead(id,owner,OffsetDateTime.now(clock))==0) throw missing();return inbox(owner,id);
    }
    @Transactional(readOnly=true)
    public Page<CommunityRepository.Event> events(UUID owner,UUID request,String action,int page,int size) {
        SubjectService.checkPage(page,size);
        if(action!=null&&!action.matches("[A-Za-z.]{1,80}")) throw ApiException.validation("action 无效。");
        return new Page<>(community.events(owner,request,action,size,(long)(page-1)*size),community.eventCount(owner,request,action),page,size);
    }
    @Transactional(readOnly=true)
    public Statistics statistics() {
        var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Hong_Kong")));
        var active=community.activeDays(today.minusDays(6),today);var questions=community.questionDays(today.withDayOfMonth(1),today);
        return new Statistics(community.count("papers"),community.count("questions"),days(today.minusDays(6),today,active),
                days(today.withDayOfMonth(1),today,questions),"Asia/Hong_Kong");
    }
    private List<DayCount> days(LocalDate first,LocalDate last,Map<LocalDate,Long> values) {
        return first.datesUntil(last.plusDays(1)).map(day->new DayCount(day,values.getOrDefault(day,0L))).toList();
    }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","消息不存在。"); }
}
