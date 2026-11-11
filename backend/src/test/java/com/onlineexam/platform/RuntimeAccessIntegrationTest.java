package com.onlineexam.platform;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.flyway.enabled=true","spring.flyway.user=${DATABASE_USER}","spring.flyway.password=${DATABASE_PASSWORD}",
    "spring.datasource.username=exam_app","spring.datasource.password=${EXAM_TEST_APP_PASSWORD}"})
class RuntimeAccessIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate runtime;
    @Test void runsTheCompleteWritePathWithTheDdlFreeRuntimeRole() throws Exception {
        assertThat(runtime.queryForObject("SELECT current_user",String.class)).isEqualTo("exam_app");
        assertThat(runtime.queryForObject("SELECT has_schema_privilege(current_user,'public','CREATE')",Boolean.class)).isFalse();
        String suffix=UUID.randomUUID().toString().substring(0,8);UUID grade=UUID.randomUUID();UUID adminId=UUID.randomUUID();
        String password="Runtime-only-test-8176";String name="RuntimeAdmin"+suffix;
        try(var connection=DriverManager.getConnection(System.getenv("DATABASE_URL"),System.getenv("DATABASE_USER"),System.getenv("DATABASE_PASSWORD"))) {
            try(var query=connection.prepareStatement("INSERT INTO grades(id,name) VALUES (?,?)")){query.setObject(1,grade);query.setString(2,"Runtime grade "+suffix);query.executeUpdate();}
            try(var query=connection.prepareStatement("INSERT INTO users(id,username,username_normalized,password_hash,role,status) VALUES (?,?,?,?,'ADMIN','ENABLED')")){
                query.setObject(1,adminId);query.setString(2,name);query.setString(3,name.toLowerCase(Locale.ROOT));query.setString(4,new BCryptPasswordEncoder(12).encode(password));query.executeUpdate();
            }
        }
        var admin=new Browser();admin.write("POST","/auth/login",Map.of("username",name,"password",password),null);
        var student=new Browser();String learner="RuntimeStudent"+suffix;
        var user=student.write("POST","/auth/register",Map.of("username",learner,"password",password,"gradeId",grade),null);
        student.write("POST","/auth/login",Map.of("username",learner,"password",password),null);
        var subject=admin.write("POST","/admin/subjects",Map.of("gradeId",grade,"name","Runtime subject"),null);
        var ids=new ArrayList<String>();
        for(String type:List.of("SINGLE","SHORT")) {
            var body=new HashMap<String,Object>(Map.of("subjectId",subject.path("id").asText(),"type",type,"difficulty",1,"score","1.00","stemHtml","Runtime question"));
            body.put("options",type.equals("SINGLE")?List.of(Map.of("id","A","text","甲"),Map.of("id","B","text","乙")):List.of());
            body.put("answer",type.equals("SINGLE")?Map.of("optionIds",List.of("A")):Map.of("text","参考"));
            ids.add(admin.write("POST","/admin/questions",body,null).path("id").asText());
        }
        var paper=admin.write("POST","/admin/papers",Map.of("subjectId",subject.path("id").asText(),"type","FIXED","draft",Map.of("name","Runtime paper","durationMinutes",20,"groups",List.of(Map.of("name","Runtime","questionIds",ids)))),null);
        String paperId=paper.path("paper").path("id").asText();admin.write("POST","/admin/papers/"+paperId+"/publish",Map.of("expectedVersion",1),null);
        String id=student.write("POST","/me/papers/"+paperId+"/attempts",null,"runtime-start").path("id").asText();
        var finalBody=Map.of("expectedVersion",1,"answerItems",List.of(Map.of("questionId",ids.get(0),"answer",Map.of("optionId","A")),Map.of("questionId",ids.get(1),"answer",Map.of("text","运行账号答案"))));
        assertThat(student.write("POST","/me/attempts/"+id+"/submit",finalBody,"runtime-submit").path("status").asText()).isEqualTo("WAIT_SELF_GRADE");
        student.write("POST","/me/attempts/"+id+"/submit",finalBody,"runtime-submit");
        var graded=student.write("POST","/me/attempts/"+id+"/self-grade",Map.of("expectedVersion",2,"scores",List.of(Map.of("questionId",ids.get(1),"score","0.50"))),"runtime-grade");
        assertThat(graded.path("attempt").path("finalScore").asText()).isEqualTo("1.50");
        var message=admin.write("POST","/admin/messages",Map.of("title","Runtime message","body","Runtime body","recipientIds",List.of(user.path("id").asText())),"runtime-message");
        String messageId=message.path("message").path("id").asText();student.write("PUT","/me/messages/"+messageId+"/read",null,null);
        var profile=student.get("/me/profile");student.write("PATCH","/me/profile",Map.of("expectedVersion",profile.path("version").asInt(),"name","运行账号","phone",""),null);
        assertThat(admin.get("/admin/attempts/"+id).path("attempt").path("finalScore").asText()).isEqualTo("1.50");
        assertThat(admin.get("/admin/statistics").path("timeZone").asText()).isEqualTo("Asia/Hong_Kong");
        admin.write("DELETE","/admin/users/"+user.path("id").asText(),Map.of("expectedVersion",1),null);
        assertThat(runtime.queryForObject("SELECT count(*) FROM deleted_account_ledger WHERE user_id=?",Integer.class,UUID.fromString(user.path("id").asText()))).isEqualTo(1);
        assertThat(runtime.queryForObject("SELECT name FROM profiles WHERE user_id=?",String.class,UUID.fromString(user.path("id").asText()))).isEmpty();
    }
    class Browser {
        HttpClient client=HttpClient.newHttpClient();String session;
        JsonNode get(String path) throws Exception {return request("GET",path,null,null,false);}
        JsonNode write(String method,String path,Object body,String key) throws Exception {return request(method,path,body,key,true);}
        JsonNode request(String method,String path,Object body,String key,boolean csrf) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path));String cookie=session;
            if(csrf){var tokenResponse=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());
                var token=json.readTree(tokenResponse.body()).path("data");String csrfCookie=tokenResponse.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];cookie=cookie==null?csrfCookie:cookie+"; "+csrfCookie;request.header(token.path("headerName").asText(),token.path("token").asText());}
            if(cookie!=null)request.header("Cookie",cookie);if(key!=null)request.header("Idempotency-Key",key);
            var response=client.send(request.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
            response.headers().allValues("Set-Cookie").stream().filter(value->value.startsWith("__Host-exam_session=")).forEach(value->session=value.split(";",2)[0]);
            var value=json.readTree(response.body());assertThat(response.statusCode()).as(method+" "+path+": "+value.path("code").asText()).isBetween(200,299);return value.path("data");
        }
    }
}
