package com.onlineexam.platform.configuration;

import com.onlineexam.platform.domain.*;
import com.onlineexam.platform.repository.CommunityRepository;
import com.onlineexam.platform.repository.SessionRepository.Principal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Aspect
@Order(1)
@Configuration(proxyBeanMethods=false)
@EnableTransactionManagement(order=0)
class AuditConfiguration {
    private static final Set<String> MUTATIONS=Set.of("register","login","changePassword","createManaged","edit","changeStatus",
            "create","publish","delete","archive","start","startTask","save","submit","confirm","send","markRead","updateProfile","avatar");
    private final CommunityRepository community;private final Clock clock;
    AuditConfiguration(CommunityRepository community,Clock clock) { this.community=community;this.clock=clock; }
    @Around("execution(public * com.onlineexam.platform.service..*(..)) && @annotation(org.springframework.transaction.annotation.Transactional)")
    Object audit(ProceedingJoinPoint call) throws Throwable {
        String method=call.getSignature().getName();
        if(!MUTATIONS.contains(method)||TransactionSynchronizationManager.isCurrentTransactionReadOnly()) return call.proceed();
        String module=call.getTarget().getClass().getSimpleName().replace("Service","");
        Object result;
        try { result=call.proceed(); }
        catch(ApiException error) {
            if("login".equals(method)) community.event(null,RequestContext.id(),module,module+"."+method,null,error.code(),OffsetDateTime.now(clock));
            throw error;
        }
        UUID actor=null;
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication!=null&&authentication.getPrincipal() instanceof Principal principal) actor=principal.id();
        if("login".equals(method)&&result instanceof Identity.Session session) actor=session.user().id();
        if("register".equals(method)&&result instanceof Identity.AccountView account) actor=account.id();
        if("login".equals(method)&&actor!=null) community.active(actor,java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Hong_Kong"))));
        UUID target=id(result);
        if(target==null) for(Object argument:call.getArgs()) if(argument instanceof UUID candidate) target=candidate;
        community.event(actor,RequestContext.id(),module,module+"."+method,target,"SUCCESS",OffsetDateTime.now(clock));
        return result;
    }
    private UUID id(Object value) {
        if(value==null) return null;
        for(String accessor:List.of("id","paper","task","attempt","message")) {
            try {
                Object result=value.getClass().getMethod(accessor).invoke(value);
                if(result instanceof UUID uuid) return uuid;
                if(!"id".equals(accessor)) { UUID nested=id(result);if(nested!=null) return nested; }
            } catch(ReflectiveOperationException ignored) { /* No business data is serialized to the audit log. */ }
        }
        return null;
    }
}
