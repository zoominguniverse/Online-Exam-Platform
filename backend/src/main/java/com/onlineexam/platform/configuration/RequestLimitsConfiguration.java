package com.onlineexam.platform.configuration;

import com.onlineexam.platform.domain.ApiException;
import com.onlineexam.platform.repository.SessionRepository.Principal;
import jakarta.servlet.http.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

@Configuration(proxyBeanMethods=false)
class RequestLimitsConfiguration implements WebMvcConfigurer {
    private record Bucket(long minute,int count) {}
    private final Map<String,Bucket> buckets=new ConcurrentHashMap<>();
    private final Clock clock;
    RequestLimitsConfiguration(Clock clock) { this.clock=clock; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                if(!(handler instanceof HandlerMethod method)||!method.getBeanType().getPackageName().startsWith("com.onlineexam.platform.controller")) return true;
                var allowed=new HashSet<String>();
                for(var parameter:method.getMethodParameters()) {
                    var annotation=parameter.getParameterAnnotation(RequestParam.class);
                    if(annotation!=null) allowed.add(!annotation.name().isEmpty()?annotation.name():!annotation.value().isEmpty()?annotation.value():parameter.getParameter().getName());
                }
                if(!allowed.containsAll(request.getParameterMap().keySet())) throw ApiException.validation("包含未声明的查询字段。");
                var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                if(authentication!=null&&authentication.getPrincipal() instanceof Principal actor) {
                    boolean read=Set.of("GET","HEAD").contains(request.getMethod());
                    String key=actor.id()+":"+read;long minute=clock.instant().getEpochSecond()/60;
                    var bucket=buckets.compute(key,(id,old)->old==null||old.minute()!=minute?new Bucket(minute,1):new Bucket(minute,old.count()+1));
                    if(buckets.size()>10000) buckets.entrySet().removeIf(entry->entry.getValue().minute()<minute);
                    if(bucket.count()>(read?6000:3000)) {
                        response.setHeader("Retry-After",Long.toString(60-clock.instant().getEpochSecond()%60));
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","请求过于频繁，请稍后重试。");
                    }
                }
                return true;
            }
        });
    }
}
