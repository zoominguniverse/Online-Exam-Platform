package com.onlineexam.platform.configuration;

import com.onlineexam.platform.repository.CommunityRepository;
import com.onlineexam.platform.service.ObservabilityService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.*;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLoggingConfiguration extends OncePerRequestFilter {
    private static final Logger log=LoggerFactory.getLogger(RequestLoggingConfiguration.class);
    private final CommunityRepository community;private final Clock clock;private final ObservabilityService metrics;
    RequestLoggingConfiguration(CommunityRepository community,Clock clock,ObservabilityService metrics) { this.community=community;this.clock=clock;this.metrics=metrics; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        UUID id=UUID.randomUUID();RequestContext.set(id);response.setHeader("X-Request-Id",id.toString());
        long started=System.nanoTime();
        try {
            MDC.put("requestId",id.toString());MDC.put("module","http");MDC.put("action",request.getMethod());
            MDC.put("actorId","anonymous");
            MDC.put("timestamp",OffsetDateTime.now(clock).toString());
            chain.doFilter(request,response);
        } finally {
            UUID actor=(UUID)request.getAttribute("exam.actor");
            if(actor!=null) MDC.put("actorId",actor.toString());
            MDC.put("result",Integer.toString(response.getStatus()));
            long millis=(System.nanoTime()-started)/1000000;
            metrics.record(millis,response.getStatus());
            if(request.getRequestURI().startsWith("/api/v1/")&&actor!=null&&response.getStatus()<400) {
                try { community.active(actor,LocalDate.now(clock.withZone(ZoneId.of("Asia/Hong_Kong")))); }
                catch(org.springframework.dao.DataAccessException error) { log.warn("Could not update authenticated activity"); }
            }
            if(request.getRequestURI().startsWith("/api/v1/")&&response.getStatus()>=400) {
                try { community.event(actor,id,"http","HTTP."+request.getMethod(),null,Integer.toString(response.getStatus()),OffsetDateTime.now(clock)); }
                catch(org.springframework.dao.DataAccessException error) { log.warn("Could not persist request failure audit"); }
            }
            log.atInfo().addKeyValue("durationMs",millis).log("Request completed");
            MDC.clear();RequestContext.clear();
        }
    }
}
