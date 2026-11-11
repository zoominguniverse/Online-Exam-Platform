package com.onlineexam.platform.service;

import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ObservabilityService {
    public record Sample(Instant at,long millis,int status) {}
    public record Metrics(long requestCount,long failureCount,double failureRate,double p95Millis,boolean databaseUp,
                          long overdueCount,long deadlineLagSeconds,int connectionPoolWaiting,int connectionPoolActive,int connectionPoolIdle,List<String> alerts,String responsibility) {}
    private static final Logger log=LoggerFactory.getLogger(ObservabilityService.class);
    private final Deque<Sample> samples=new ArrayDeque<>();
    private long windowFailures;
    private final JdbcClient jdbc;private final Clock clock;private final javax.sql.DataSource dataSource;
    private Instant highErrorSince;
    private List<String> lastAlerts=List.of();
    public ObservabilityService(JdbcClient jdbc,Clock clock,javax.sql.DataSource dataSource) { this.jdbc=jdbc;this.clock=clock;this.dataSource=dataSource; }
    public synchronized void record(long millis,int status) {
        Instant now=clock.instant();samples.add(new Sample(now,millis,status));if(status>=500)windowFailures++;
        trim(now);
        long failed=windowFailures;
        if(!samples.isEmpty()&&failed/(double)samples.size()>.05) { if(highErrorSince==null) highErrorSince=now; }
        else highErrorSince=null;
    }
    private void trim(Instant now) {
        while(!samples.isEmpty()&&(samples.peek().at().isBefore(now.minusSeconds(300))||samples.size()>100000)) if(samples.poll().status()>=500)windowFailures--;
        if(samples.isEmpty()||windowFailures/(double)samples.size()<=.05)highErrorSince=null;
    }
    public synchronized Metrics metrics() {
        Instant now=clock.instant();trim(now);
        var snapshot=List.copyOf(samples);long failures=snapshot.stream().filter(item->item.status()>=500).count();
        var durations=snapshot.stream().mapToLong(Sample::millis).sorted().toArray();
        double p95=durations.length==0?0:durations[(int)Math.ceil(durations.length*.95)-1];
        boolean up=true;long overdue=0,lag=0;
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            var row=jdbc.sql("SELECT count(*) AS n,COALESCE(EXTRACT(EPOCH FROM (:now-MIN(deadline_at))),0)::bigint AS lag "
                    +"FROM attempts WHERE status='IN_PROGRESS' AND deadline_at<=:now")
                    .param("now",OffsetDateTime.now(clock)).query().singleRow();
            overdue=((Number)row.get("n")).longValue();lag=Math.max(0,((Number)row.get("lag")).longValue());
        } catch(org.springframework.dao.DataAccessException error) { up=false; }
        var alerts=new ArrayList<String>();
        if(!up) alerts.add("DATABASE_UNAVAILABLE");
        if(lag>30) alerts.add("DEADLINE_BACKLOG");
        if(highErrorSince!=null&&!now.isBefore(highErrorSince.plusSeconds(300))) alerts.add("ERROR_RATE_HIGH");
        var pool=dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari?hikari.getHikariPoolMXBean():null;
        return new Metrics(snapshot.size(),failures,snapshot.isEmpty()?0:failures/(double)snapshot.size(),p95,up,overdue,lag,
                pool==null?0:pool.getThreadsAwaitingConnection(),pool==null?0:pool.getActiveConnections(),pool==null?0:pool.getIdleConnections(),List.copyOf(alerts),"值班运维");
    }
    public synchronized void inspect() {
        var current=metrics();
        if(!lastAlerts.equals(current.alerts())) {
            log.atWarn().addKeyValue("module","operations").addKeyValue("action","alert.changed")
                .addKeyValue("requestId",UUID.randomUUID().toString()).addKeyValue("actorId","system").addKeyValue("timestamp",OffsetDateTime.now(clock).toString())
                .addKeyValue("result",current.alerts()).log("Operations alert state changed; responsibility=值班运维");
            lastAlerts=current.alerts();
        }
    }
}
