package com.onlineexam.platform.repository;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CommunityRepository {
    public record Profile(UUID id,String username,String role,String name,String phone,LocalDate birthday,
                          UUID avatarId,int version,OffsetDateTime createdAt) {}
    public record Message(UUID id,String title,String body,OffsetDateTime sentAt,OffsetDateTime readAt,long recipientCount,long readCount) {}
    public record Recipient(UUID id,String username,OffsetDateTime readAt) {}
    public record Event(UUID id,UUID requestId,UUID actorId,String module,String action,UUID targetId,String result,OffsetDateTime createdAt) {}
    private final JdbcClient jdbc;
    public CommunityRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public Profile profile(UUID id) {
        jdbc.sql("INSERT INTO profiles(user_id) VALUES (:id) ON CONFLICT DO NOTHING").param("id",id).update();
        return jdbc.sql("SELECT u.id,u.username,u.role,u.created_at,p.* FROM users u JOIN profiles p ON p.user_id=u.id WHERE u.id=:id")
                .param("id",id).query((row,index)->new Profile(row.getObject("id",UUID.class),row.getString("username"),row.getString("role"),
                        row.getString("name"),row.getString("phone"),row.getObject("birthday",LocalDate.class),row.getObject("avatar_id",UUID.class),
                        row.getInt("version"),row.getObject("created_at",OffsetDateTime.class))).single();
    }
    public void updateProfile(UUID id,String name,String phone,LocalDate birthday) {
        jdbc.sql("UPDATE profiles SET name=:name,phone=:phone,birthday=:birthday,version=version+1 WHERE user_id=:id")
                .param("id",id).param("name",name).param("phone",phone).param("birthday",birthday).update();
    }
    public boolean ownsImage(UUID owner,UUID image) {
        return jdbc.sql("SELECT id FROM attachments WHERE owner_id=:owner AND id=:id FOR UPDATE")
                .param("owner",owner).param("id",image).query(UUID.class).optional().isPresent();
    }
    public void avatar(UUID owner,UUID image) {
        jdbc.sql("UPDATE profiles SET avatar_id=:image,version=version+1 WHERE user_id=:owner")
                .param("owner",owner).param("image",image).update();
    }
    public List<UUID> recipients(Set<UUID> ids) {
        return jdbc.sql("SELECT id FROM users WHERE id IN (:ids) AND status='ENABLED' ORDER BY id FOR SHARE")
                .param("ids",ids).query(UUID.class).list();
    }
    public void send(UUID id,UUID sender,String title,String body,List<UUID> recipients,OffsetDateTime now) {
        jdbc.sql("INSERT INTO messages(id,sender_id,title,body,sent_at) VALUES (:id,:sender,:title,:body,:now)")
                .param("id",id).param("sender",sender).param("title",title).param("body",body).param("now",now).update();
        for(UUID recipient:recipients) jdbc.sql("INSERT INTO message_recipients(message_id,user_id) VALUES (:id,:user)")
                .param("id",id).param("user",recipient).update();
    }
    private static final String MESSAGE="""
            SELECT m.id,m.title,m.body,m.sent_at,r.read_at,
              (SELECT count(*) FROM message_recipients x WHERE x.message_id=m.id) AS recipient_count,
              (SELECT count(*) FROM message_recipients x WHERE x.message_id=m.id AND x.read_at IS NOT NULL) AS read_count
            FROM messages m LEFT JOIN message_recipients r ON r.message_id=m.id AND r.user_id=CAST(:owner AS uuid)
            WHERE (CAST(:owner AS uuid) IS NULL OR r.user_id=CAST(:owner AS uuid))
            """;
    private static final org.springframework.jdbc.core.RowMapper<Message> MESSAGE_ROW=(row,index)->new Message(
            row.getObject("id",UUID.class),row.getString("title"),row.getString("body"),row.getObject("sent_at",OffsetDateTime.class),
            row.getObject("read_at",OffsetDateTime.class),row.getLong("recipient_count"),row.getLong("read_count"));
    public Optional<Message> message(UUID id,UUID owner) {
        return jdbc.sql(MESSAGE+" AND m.id=:id").param("owner",owner).param("id",id).query(MESSAGE_ROW).optional();
    }
    public List<Message> messages(UUID owner,int size,long offset) {
        return jdbc.sql(MESSAGE+" ORDER BY m.sent_at DESC,m.id LIMIT :size OFFSET :offset").param("owner",owner)
                .param("size",size).param("offset",offset).query(MESSAGE_ROW).list();
    }
    public long messageCount(UUID owner) {
        return jdbc.sql("SELECT count(*) FROM messages m WHERE CAST(:owner AS uuid) IS NULL OR EXISTS "
                +"(SELECT 1 FROM message_recipients r WHERE r.message_id=m.id AND r.user_id=CAST(:owner AS uuid))")
                .param("owner",owner).query(Long.class).single();
    }
    public List<Recipient> recipients(UUID id) {
        return jdbc.sql("SELECT r.user_id,u.username,u.status,r.read_at FROM message_recipients r JOIN users u ON u.id=r.user_id "
                +"WHERE r.message_id=:id ORDER BY r.user_id").param("id",id).query((row,index)->new Recipient(row.getObject("user_id",UUID.class),
                        "DELETED".equals(row.getString("status"))?"已删除账号":row.getString("username"),row.getObject("read_at",OffsetDateTime.class))).list();
    }
    public int markRead(UUID id,UUID owner,OffsetDateTime now) {
        return jdbc.sql("UPDATE message_recipients SET read_at=COALESCE(read_at,:now) WHERE message_id=:id AND user_id=:owner")
                .param("id",id).param("owner",owner).param("now",now).update();
    }
    public void event(UUID actor,UUID request,String module,String action,UUID target,String result,OffsetDateTime now) {
        jdbc.sql("INSERT INTO audit_events(id,actor_id,request_id,module,action,target_id,result,created_at) VALUES (:id,:actor,:request,:module,:action,:target,:result,:now)")
                .param("id",UUID.randomUUID()).param("actor",actor).param("request",request).param("module",module).param("action",action)
                .param("target",target).param("result",result).param("now",now).update();
    }
    private static final String EVENTS=" FROM audit_events WHERE (CAST(:owner AS uuid) IS NULL OR actor_id=CAST(:owner AS uuid)) "
            +"AND (CAST(:request AS uuid) IS NULL OR request_id=CAST(:request AS uuid)) AND (:action IS NULL OR action=:action)";
    public List<Event> events(UUID owner,UUID request,String action,int size,long offset) {
        return eventFilter("SELECT * "+EVENTS+" ORDER BY created_at DESC,id LIMIT :size OFFSET :offset",owner,request,action)
                .param("size",size).param("offset",offset).query((row,index)->new Event(row.getObject("id",UUID.class),row.getObject("request_id",UUID.class),
                        row.getObject("actor_id",UUID.class),row.getString("module"),row.getString("action"),row.getObject("target_id",UUID.class),
                        row.getString("result"),row.getObject("created_at",OffsetDateTime.class))).list();
    }
    public long eventCount(UUID owner,UUID request,String action) { return eventFilter("SELECT count(*) "+EVENTS,owner,request,action).query(Long.class).single(); }
    private JdbcClient.StatementSpec eventFilter(String sql,UUID owner,UUID request,String action) {
        return jdbc.sql(sql).param("owner",owner).param("request",request).param("action",action,java.sql.Types.VARCHAR);
    }
    public void active(UUID owner,LocalDate day) {
        jdbc.sql("INSERT INTO user_daily_activity(user_id,day) VALUES (:owner,:day) ON CONFLICT DO NOTHING").param("owner",owner).param("day",day).update();
    }
    public long count(String table) {
        if(!Set.of("papers","questions").contains(table)) throw new IllegalArgumentException();
        return jdbc.sql("SELECT count(*) FROM "+table+" WHERE NOT archived").query(Long.class).single();
    }
    public Map<LocalDate,Long> activeDays(LocalDate first,LocalDate last) {
        var values=new HashMap<LocalDate,Long>();
        jdbc.sql("SELECT day,count(*) AS n FROM user_daily_activity WHERE day BETWEEN :first AND :last GROUP BY day")
                .param("first",first).param("last",last).query((row,index)->{ values.put(row.getObject("day",LocalDate.class),row.getLong("n"));return 0; }).list();
        return values;
    }
    public Map<LocalDate,Long> questionDays(LocalDate first,LocalDate last) {
        var values=new HashMap<LocalDate,Long>();
        jdbc.sql("SELECT (created_at AT TIME ZONE 'Asia/Hong_Kong')::date AS day,count(*) AS n FROM questions "
                +"WHERE created_at>=:first AND created_at<:last GROUP BY day")
                .param("first",first.atStartOfDay(ZoneId.of("Asia/Hong_Kong")).toOffsetDateTime())
                .param("last",last.plusDays(1).atStartOfDay(ZoneId.of("Asia/Hong_Kong")).toOffsetDateTime())
                .query((row,index)->{ values.put(row.getObject("day",LocalDate.class),row.getLong("n"));return 0; }).list();
        return values;
    }
}
