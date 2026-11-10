package com.onlineexam.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.onlineexam.platform.domain.ApiException;
import com.onlineexam.platform.integration.WeChatIdentityProvider;
import com.onlineexam.platform.integration.WeChatIdentityProvider.Subject;
import com.onlineexam.platform.service.AttachmentService;
import com.onlineexam.platform.service.IdentityService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.flyway.enabled=true")
@Import(IdentityIntegrationTest.TestClockConfiguration.class)
class IdentityIntegrationTest {
    private static final String PASSWORD = "Only-a-test-secret-8176";
    private static final String USERS = "/admin/users";
    private static final UUID GRADE = UUID.fromString("62f9b743-a13e-4267-8810-3f41c8120110");

    @Autowired ObjectMapper json;
    @Autowired IdentityService identity;
    @Autowired AttachmentService attachments;
    @Autowired AdjustableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Value("${PRIVATE_STORAGE_ROOT}") String privateRoot;
    @MockitoBean WeChatIdentityProvider wechat;
    @LocalServerPort int port;

    @BeforeEach
    void prepare() {
        clock.override = null;
        jdbc.execute("""
                TRUNCATE task_recipients, task_papers, tasks, attachment_links, attachments,
                    paper_items, paper_versions, papers, question_versions, questions, subjects
                """);
        jdbc.update("DELETE FROM wechat_bindings");
        jdbc.update("DELETE FROM wechat_used_codes");
        jdbc.update("DELETE FROM web_sessions");
        jdbc.update("DELETE FROM login_limits");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM grades");
        jdbc.update("INSERT INTO grades (id, name) VALUES (?, '演示年级') ON CONFLICT (id) DO NOTHING", GRADE);
        identity.bootstrap("MainAdmin", PASSWORD);
    }

    @Test
    void validatesRegistrationAndKeepsCredentialsOutOfResponsesAndLogs(CapturedOutput output) throws Exception {
        var student = new Browser();
        assertThat(student.get("/catalog/grades").data().get(0).path("id").asText()).isEqualTo(GRADE.toString());
        var valid = Map.of("username", "Learner", "password", PASSWORD, "gradeId", GRADE);
        assertThat(student.request("POST", "/auth/register", valid, false).status()).isEqualTo(403);
        assertThat(student.write("POST", "/auth/register",
                Map.of("username", "Injected", "password", PASSWORD, "gradeId", GRADE, "role", "ADMIN"))
                .status()).isEqualTo(400);
        assertThat(student.write("POST", "/auth/register", Map.of("username", "BadGrade", "password", PASSWORD,
                "gradeId", UUID.randomUUID())).status()).isEqualTo(400);
        assertThat(student.write("POST", "/auth/register", Map.of("username", "BadPassword", "password", "short",
                "gradeId", GRADE)).status()).isEqualTo(400);
        assertThat(student.write("POST", "/auth/register", valid).data().path("role").asText()).isEqualTo("STUDENT");
        assertThat(student.write("POST", "/auth/register", Map.of("username", "  ＬＥＡＲＮＥＲ  ",
                "password", PASSWORD, "gradeId", GRADE)).status()).isEqualTo(400);
        assertThat(student.login("Learner", "incorrect-password").status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_sessions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE username_normalized='learner'", String.class))
                .startsWith("$2a$12$").doesNotContain(PASSWORD);

        var signedIn = student.login("  LEARNER ", PASSWORD);
        assertThat(signedIn.status()).isEqualTo(200);
        assertThat(signedIn.data().has("passwordHash")).isFalse();
        assertThat(signedIn.response().headers().allValues("Set-Cookie"))
                .anySatisfy(header -> assertThat(header).startsWith("__Host-exam_session=")
                        .contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax"));
        assertThat(student.get("/me").data().path("id").asText()).isEqualTo(signedIn.data().path("id").asText());
        assertThat(student.get(USERS).status()).isEqualTo(403);
        assertThat(student.write("POST", USERS, valid).status()).isEqualTo(403);
        var old = new Browser();
        old.session = student.session;
        assertThat(student.write("POST", "/auth/logout", null).status()).isEqualTo(200);
        assertThat(old.get("/me").status()).isEqualTo(401);
        assertThat(output.getAll()).doesNotContain(PASSWORD, "incorrect-password");
    }

    @Test
    void managesAccountsAndRevokesAllSessionsWhenDisabled() throws Exception {
        var admin = new Browser();
        assertThat(admin.login("MainAdmin", PASSWORD).status()).isEqualTo(200);
        var created = admin.write("POST", USERS, Map.of("username", "NewStudent", "password", PASSWORD,
                "role", "STUDENT", "gradeId", GRADE));
        assertThat(created.status()).isEqualTo(201);
        String path = USERS + "/" + created.data().path("id").asText();
        assertThat(admin.get(USERS + "?role=STUDENT&gradeId=" + GRADE + "&sort=username").data().path("total").asInt())
                .isEqualTo(1);
        assertThat(admin.get(USERS + "?sort=status").status()).isEqualTo(400);
        var first = new Browser();
        var second = new Browser();
        assertThat(first.login("NewStudent", PASSWORD).status()).isEqualTo(200);
        assertThat(second.login("NewStudent", PASSWORD).status()).isEqualTo(200);
        var edit = admin.write("PATCH", path, Map.of("username", "Renamed", "role", "STUDENT",
                "gradeId", GRADE, "expectedVersion", 1));
        assertThat(edit.status()).isEqualTo(200);
        assertThat(admin.write("POST", path + "/disable", Map.of("expectedVersion", 1)).status()).isEqualTo(409);
        assertThat(admin.write("POST", path + "/disable", Map.of("expectedVersion", 2)).status()).isEqualTo(200);
        assertThat(first.get("/me").status()).isEqualTo(401);
        assertThat(second.get("/me").status()).isEqualTo(401);
        assertThat(first.login("Renamed", PASSWORD).status()).isEqualTo(401);
        assertThat(admin.write("POST", path + "/enable", Map.of("expectedVersion", 3)).status()).isEqualTo(200);
        assertThat(second.get("/me").status()).isEqualTo(401);
        assertThat(first.login("Renamed", PASSWORD).status()).isEqualTo(200);
        assertThat(admin.write("DELETE", path, Map.of("expectedVersion", 4)).status()).isEqualTo(200);
        assertThat(admin.get(path).status()).isEqualTo(404);
        assertThat(first.get("/me").status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?",
                String.class, UUID.fromString(created.data().path("id").asText()))).isEqualTo("DELETED");
    }

    @Test
    void protectsTheLastEnabledAdministratorAcrossConcurrentHttpRequests() throws Exception {
        var first = new Browser();
        var second = new Browser();
        String firstId = first.login("MainAdmin", PASSWORD).data().path("id").asText();
        String secondId = first.write("POST", USERS,
                Map.of("username", "OtherAdmin", "password", PASSWORD, "role", "ADMIN")).data().path("id").asText();
        assertThat(second.login("OtherAdmin", PASSWORD).status()).isEqualTo(200);
        var toFirst = second.prepare("POST", USERS + "/" + firstId + "/disable", Map.of("expectedVersion", 1), true);
        var toSecond = first.prepare("DELETE", USERS + "/" + secondId, Map.of("expectedVersion", 1), true);
        var resultA = second.http.sendAsync(toFirst, HttpResponse.BodyHandlers.discarding());
        var resultB = first.http.sendAsync(toSecond, HttpResponse.BodyHandlers.discarding());
        CompletableFuture.allOf(resultA, resultB).get(10, TimeUnit.SECONDS);
        assertThat(java.util.List.of(resultA.join().statusCode(), resultB.join().statusCode()))
                .containsExactlyInAnyOrder(200, 401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE role='ADMIN' AND status='ENABLED'", Integer.class))
                .isEqualTo(1);
        var remaining = resultA.join().statusCode() == 200 ? second : first;
        var ownId = remaining.get("/me").data().path("id").asText();
        assertThat(remaining.write("POST", USERS + "/" + ownId + "/disable", Map.of("expectedVersion", 1)).status())
                .isEqualTo(409);
    }

    @Test
    void consumesCodesAndRevokesTokensWhilePreservingWebIdentity() throws Exception {
        var web = new Browser();
        String user = web.write("POST", "/auth/register", Map.of("username", "WechatStudent",
                "password", PASSWORD, "gradeId", GRADE)).data().path("id").asText();
        assertThat(web.login("WechatStudent", PASSWORD).status()).isEqualTo(200);
        var wx = new Browser();
        subject("first-bind", "same-account");
        subject("next-login", "same-account");
        subject("duplicate-bind", "different-account");
        var bound = wxBind(wx, "first-bind", "WechatStudent");
        assertThat(bound.status()).isEqualTo(200);
        String firstToken = bound.data().path("token").asText();
        assertThat(bound.data().path("user").path("id").asText()).isEqualTo(user);
        assertThat(wxBind(wx, "duplicate-bind", "WechatStudent").status()).isEqualTo(409);
        var next = wx.write("POST", "/auth/wechat/login", Map.of("code", "next-login"));
        assertThat(next.status()).isEqualTo(200);
        String secondToken = next.data().path("token").asText();
        assertThat(bearer("GET", "/me", firstToken).data().path("id").asText()).isEqualTo(user);
        assertThat(bearer("GET", "/me", secondToken).data().path("id").asText()).isEqualTo(user);
        assertThat(bearer("GET", USERS, secondToken).status()).isIn(401, 403);
        assertThat(bearer("DELETE", "/auth/wechat/binding", secondToken).status()).isEqualTo(200);
        assertThat(bearer("GET", "/me", firstToken).status()).isEqualTo(401);
        assertThat(bearer("GET", "/me", secondToken).status()).isEqualTo(401);
        assertThat(wxBind(wx, "first-bind", "WechatStudent").status()).isEqualTo(400);
        assertThat(web.get("/me").data().path("id").asText()).isEqualTo(user);
    }

    @Test
    void handlesUpstreamFailuresAndPreventsConcurrentBindingToTwoAccounts() throws Exception {
        var wx = new Browser();
        var enrollment = new Browser();
        for (String name : java.util.List.of("FirstStudent", "SecondStudent")) {
            assertThat(enrollment.write("POST", "/auth/register", Map.of("username", name,
                    "password", PASSWORD, "gradeId", GRADE)).status()).isEqualTo(201);
        }
        when(wechat.exchange("invalid")).thenThrow(ApiException.validation("登录码无效。"));
        when(wechat.exchange("timeout")).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "DEPENDENCY_UNAVAILABLE", "微信服务暂不可用。"));
        assertThat(wxBind(wx, "invalid", "FirstStudent").status()).isEqualTo(400);
        assertThat(wxBind(wx, "timeout", "FirstStudent").status()).isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wechat_bindings", Integer.class)).isZero();
        subject("code-a", "shared-account");
        subject("code-b", "shared-account");
        var a = wx.http.sendAsync(wx.prepare("POST", "/auth/wechat/bind", Map.of("code", "code-a",
                "username", "FirstStudent", "password", PASSWORD), false), HttpResponse.BodyHandlers.ofString());
        var b = wx.http.sendAsync(wx.prepare("POST", "/auth/wechat/bind", Map.of("code", "code-b",
                "username", "SecondStudent", "password", PASSWORD), false), HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(a, b).get(10, TimeUnit.SECONDS);
        assertThat(java.util.List.of(a.join().statusCode(), b.join().statusCode())).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wechat_bindings", Integer.class)).isEqualTo(1);
        var winner = json.readTree((a.join().statusCode() == 200 ? a : b).join().body()).path("data");
        var admin = new Browser();
        assertThat(admin.login("MainAdmin", PASSWORD).status()).isEqualTo(200);
        var user = winner.path("user");
        assertThat(admin.write("POST", USERS + "/" + user.path("id").asText() + "/disable",
                Map.of("expectedVersion", user.path("version").asInt())).status()).isEqualTo(200);
        assertThat(bearer("GET", "/me", winner.path("token").asText()).status()).isEqualTo(401);
        subject("new-code", "shared-account");
        assertThat(wx.write("POST", "/auth/wechat/login", Map.of("code", "new-code")).status()).isEqualTo(401);
    }

    @Test
    void createsAllQuestionTypesAndPreservesVersionsWhenArchived() throws Exception {
        var admin = new Browser();
        assertThat(admin.login("MainAdmin", PASSWORD).status()).isEqualTo(200);
        String subjects = "/admin/subjects";
        String questions = "/admin/questions";
        assertThat(admin.write("POST", subjects, Map.of("gradeId", UUID.randomUUID(), "name", "Math")).status())
                .isEqualTo(400);
        var removable = admin.write("POST", subjects, Map.of("gradeId", GRADE, "name", "Temporary"));
        assertThat(removable.status()).isEqualTo(201);
        String removablePath = subjects + "/" + removable.data().path("id").asText();
        assertThat(admin.write("PATCH", removablePath, Map.of("expectedVersion", 1, "name", "Renamed")).status())
                .isEqualTo(200);
        assertThat(admin.write("DELETE", removablePath, Map.of("expectedVersion", 2)).status()).isEqualTo(200);
        assertThat(admin.get(removablePath).status()).isEqualTo(404);
        var math = admin.write("POST", subjects, Map.of("gradeId", GRADE, "name", "Math"));
        assertThat(math.status()).isEqualTo(201);
        assertThat(admin.write("POST", subjects, Map.of("gradeId", GRADE, "name", "  ｍａｔｈ  ")).status())
                .isEqualTo(400);
        String subject = math.data().path("id").asText();
        assertThat(admin.get(subjects + "?gradeId=" + GRADE).data().path("total").asInt()).isEqualTo(1);
        var options = java.util.List.of(Map.of("id", "A", "text", "1"), Map.of("id", "B", "text", "2"));
        var base = new java.util.HashMap<String, Object>(Map.of("subjectId", subject, "difficulty", 3, "score", "2.35",
                "stemHtml", "<p onclick='bad()'>题干</p><script>bad()</script><a href='javascript:bad()'>正文</a>",
                "options", options, "type", "SINGLE", "answer", Map.of("optionIds", java.util.List.of("B"))));
        var single = admin.write("POST", questions, base);
        assertThat(single.status()).isEqualTo(201);
        assertThat(single.data().path("stemHtml").asText()).isEqualTo("<p>题干</p>正文");
        String path = questions + "/" + single.data().path("id").asText();
        var variants = java.util.List.of(
                Map.of("type", "MULTIPLE", "answer", Map.of("optionIds", java.util.List.of("A", "B"))),
                Map.of("type", "TRUE_FALSE", "options", java.util.List.of(), "answer", Map.of("optionIds", java.util.List.of("true"))),
                Map.of("type", "GAP", "options", java.util.List.of(), "stemHtml", "内容 <span data-gap=\"1\">___</span>",
                        "answer", Map.of("gaps", java.util.List.of("答案"))),
                Map.of("type", "SHORT", "options", java.util.List.of(), "answer", Map.of("text", "参考内容")));
        for (var variant : variants) {
            var draft = new java.util.HashMap<>(base);
            draft.putAll(variant);
            var created = admin.write("POST", questions, draft);
            assertThat(created.status()).isEqualTo(201);
            assertThat(created.data().path("type").asText()).isEqualTo(variant.get("type"));
            assertThat(admin.get(questions + "?subjectId=" + subject + "&type=" + variant.get("type"))
                    .data().path("total").asInt()).isEqualTo(1);
        }
        assertThat(admin.write("DELETE", subjects + "/" + subject, Map.of("expectedVersion", 1)).status())
                .isEqualTo(409);
        for (var invalid : java.util.List.of(
                Map.of("score", "0.00"), Map.of("score", "-1.00"), Map.of("subjectId", UUID.randomUUID()),
                Map.of("answer", Map.of("optionIds", java.util.List.of("X"))),
                Map.of("type", "TRUE_FALSE", "options", java.util.List.of(), "answer", Map.of()),
                Map.of("type", "GAP", "options", java.util.List.of(), "answer", Map.of("gaps", java.util.List.of("无空位"))))) {
            var draft = new java.util.HashMap<>(base);
            draft.putAll(invalid);
            assertThat(admin.write("POST", questions, draft).status()).isEqualTo(400);
        }
        assertThat(admin.get(questions + "?subjectId=" + subject).data().path("total").asInt()).isEqualTo(5);
        base.put("score", "3.00");
        assertThat(admin.write("PATCH", path, Map.of("question", base, "expectedVersion", 1)).status()).isEqualTo(200);
        assertThat(admin.write("PATCH", path, Map.of("question", base, "expectedVersion", 1)).status()).isEqualTo(409);
        assertThat(admin.write("DELETE", path, Map.of("expectedVersion", 2)).status()).isEqualTo(200);
        assertThat(admin.get(path + "?version=1").data().path("score").asText()).isEqualTo("2.35");
        assertThat(admin.get(questions + "?subjectId=" + subject).data().path("total").asInt()).isEqualTo(4);
    }

    @Test
    void validatesPrivateImagesAndCleansRolledBackReferences() throws Exception {
        var owner = new Browser();
        assertThat(owner.login("MainAdmin", PASSWORD).status()).isEqualTo(200);
        var other = new Browser();
        assertThat(owner.write("POST", USERS,
                Map.of("username", "ImageReviewer", "password", PASSWORD, "role", "ADMIN")).status()).isEqualTo(201);
        assertThat(other.login("ImageReviewer", PASSWORD).status()).isEqualTo(200);
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "PNG", png);
        var webp = java.util.Base64.getDecoder().decode(
                "UklGRjoAAABXRUJQVlA4IC4AAADQAQCdASoCAAIAAUAmJaACdLoB+AADsAD+82mX/mwIGaH0wf+mkeNI8aR8poAA");
        assertThat(owner.upload("fake.png", "<svg>fake</svg>".getBytes()).status()).isEqualTo(400);
        assertThat(owner.upload("huge.png", new byte[5 * 1024 * 1024 + 1]).status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attachments", Integer.class)).isZero();
        var uploaded = owner.upload("valid.png", png.toByteArray());
        assertThat(uploaded.status()).isEqualTo(201);
        String firstId = uploaded.data().path("id").asText();
        assertThat(owner.get("/attachments/" + firstId).status()).isEqualTo(404);
        var staged = owner.upload("valid.webp", webp);
        assertThat(staged.status()).isEqualTo(201);
        String stagedId = staged.data().path("id").asText();
        UUID stagedKey = jdbc.queryForObject("SELECT storage_key FROM attachments WHERE id = ?", UUID.class,
                UUID.fromString(stagedId));

        var subject = owner.write("POST", "/admin/subjects", Map.of("gradeId", GRADE, "name", "Images")).data();
        var draft = new java.util.HashMap<String, Object>(Map.of("subjectId", subject.path("id").asText(),
                "type", "SHORT", "difficulty", 3, "score", "1.00", "answer", Map.of("text", "参考内容")));
        draft.put("stemHtml", "<img data-attachment='" + stagedId + "'><img data-attachment='"
                + new UUID(Long.MAX_VALUE, Long.MAX_VALUE) + "'>");
        assertThat(owner.write("POST", "/admin/questions", draft).status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attachment_links", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM questions", Integer.class)).isZero();

        draft.put("stemHtml", "<img data-attachment='" + firstId + "' src='https://example.invalid/img' onerror='bad()'>");
        assertThat(other.write("POST", "/admin/questions", draft).status()).isEqualTo(400);
        var created = owner.write("POST", "/admin/questions", draft);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.data().path("stemHtml").asText()).contains("data-attachment=\"" + firstId)
                .doesNotContain("src=", "example.invalid", "onerror");
        var imageRequest = owner.prepare("GET", "/attachments/" + firstId, null, false);
        var image = owner.http.send(imageRequest, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(image.body()).isEqualTo(png.toByteArray());
        assertThat(new Browser().get("/attachments/" + firstId).status()).isEqualTo(401);
        assertThat(other.get("/attachments/" + firstId).status()).isEqualTo(404);
        String questionPath = "/admin/questions/" + created.data().path("id").asText() + "/versions/";
        assertThat(other.http.send(other.prepare("GET", questionPath + "1/attachments/" + firstId, null, false),
                HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(200);
        assertThat(other.get(questionPath + "2/attachments/" + firstId).status()).isEqualTo(404);

        var stagedFile = Path.of(privateRoot, stagedKey + ".img");
        assertThat(Files.exists(stagedFile)).isTrue();
        jdbc.update("UPDATE attachments SET created_at = CURRENT_TIMESTAMP - interval '25 hours'");
        attachments.clean();
        assertThat(Files.exists(stagedFile)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attachments", Integer.class)).isEqualTo(1);
    }

    @Test
    void publishesAllPaperTypesAndFiltersStudentsAtTheExactWindowBoundaries() throws Exception {
        clock.override = Instant.parse("2026-11-10T00:00:00Z");
        var admin = new Browser();
        assertThat(admin.login("MainAdmin", PASSWORD).status()).isEqualTo(200);
        var student = new Browser();
        assertThat(student.write("POST", "/auth/register",
                Map.of("gradeId", GRADE, "username", "PaperStudent", "password", PASSWORD)).status()).isEqualTo(201);
        assertThat(student.login("PaperStudent", PASSWORD).status()).isEqualTo(200);
        String subject = admin.write("POST", "/admin/subjects",
                Map.of("gradeId", GRADE, "name", "Paper Math")).data().path("id").asText();
        String otherSubject = admin.write("POST", "/admin/subjects",
                Map.of("gradeId", GRADE, "name", "Other Math")).data().path("id").asText();
        String first = createShortQuestion(admin, subject, "1.25");
        String second = createShortQuestion(admin, subject, "2.50");
        String foreign = createShortQuestion(admin, otherSubject, "1.00");
        var groups = java.util.List.of(Map.of("name", "第一组", "questionIds", java.util.List.of(second, first)));
        var ordinary = Map.of("name", "练习", "durationMinutes", 30, "groups", groups);
        var timed = Map.of("name", "时段练习", "durationMinutes", 30, "groups", groups,
                "opensAt", "2026-11-10T00:01:00Z", "closesAt", "2026-11-10T00:02:00Z");
        String papers = "/admin/papers";
        String fixedId = "";
        String timedId = "";
        for (String type : java.util.List.of("FIXED", "TIMED", "TASK")) {
            var created = admin.write("POST", papers, Map.of("subjectId", subject,
                    "type", type, "draft", "TIMED".equals(type) ? timed : ordinary));
            assertThat(created.status()).isEqualTo(201);
            String id = created.data().path("paper").path("id").asText();
            assertThat(student.get("/me/papers/" + id).status()).isEqualTo(404);
            var published = admin.write("POST", papers + "/" + id + "/publish", Map.of("expectedVersion", 1));
            assertThat(published.status()).isEqualTo(200);
            assertThat(published.data().path("totalScore").asText()).isEqualTo("3.75");
            assertThat(published.data().path("items").get(0).path("questionId").asText()).isEqualTo(second);
            assertThat(published.data().path("content").path("publishedAt").asText()).isNotBlank();
            if ("TASK".equals(type)) assertThat(student.get("/me/papers/" + id).status()).isEqualTo(404);
            if ("FIXED".equals(type)) fixedId = id;
            if ("TIMED".equals(type)) timedId = id;
        }
        assertThat(student.get("/me/papers?status=UPCOMING").data().path("total").asInt()).isEqualTo(1);
        assertThat(student.get("/me/papers/" + timedId).data().path("status").asText()).isEqualTo("UPCOMING");
        clock.override = Instant.parse("2026-11-10T00:01:00Z");
        assertThat(student.get("/me/papers/" + timedId).data().path("status").asText()).isEqualTo("OPEN");
        clock.override = Instant.parse("2026-11-10T00:02:00Z");
        assertThat(student.get("/me/papers/" + timedId).data().path("status").asText()).isEqualTo("CLOSED");
        assertThat(student.get("/me/papers?status=OPEN&type=FIXED").data().path("total").asInt()).isEqualTo(1);
        assertThat(student.get("/me/papers/" + fixedId).data().has("items")).isFalse();
        UUID otherGrade = UUID.randomUUID();
        jdbc.update("INSERT INTO grades(id, name) VALUES (?, '其他年级')", otherGrade);
        var other = new Browser();
        assertThat(other.write("POST", "/auth/register",
                Map.of("gradeId", otherGrade, "username", "OtherGrade", "password", PASSWORD)).status()).isEqualTo(201);
        assertThat(other.login("OtherGrade", PASSWORD).status()).isEqualTo(200);
        assertThat(other.get("/me/papers/" + fixedId).status()).isEqualTo(404);

        var wrong = Map.of("name", "跨学科", "durationMinutes", 30,
                "groups", java.util.List.of(Map.of("name", "组", "questionIds", java.util.List.of(foreign))));
        assertThat(admin.write("POST", papers, Map.of("subjectId", subject, "type", "FIXED", "draft", wrong)).status())
                .isEqualTo(400);
        var empty = admin.write("POST", papers, Map.of("subjectId", subject, "type", "FIXED",
                "draft", Map.of("name", "空卷", "durationMinutes", 15)));
        String emptyPath = papers + "/" + empty.data().path("paper").path("id").asText();
        assertThat(admin.write("POST", emptyPath + "/publish", Map.of("expectedVersion", 1)).status()).isEqualTo(400);
        assertThat(admin.write("DELETE", emptyPath, Map.of("expectedVersion", 1)).status()).isEqualTo(200);
        assertThat(admin.get(emptyPath).status()).isEqualTo(404);
        var backwards = new java.util.HashMap<>(timed);
        backwards.put("closesAt", "2026-11-10T00:00:59Z");
        var invalid = admin.write("POST", papers, Map.of("subjectId", subject, "type", "TIMED", "draft", backwards));
        assertThat(invalid.status()).isEqualTo(201);
        assertThat(admin.write("POST", papers + "/" + invalid.data().path("paper").path("id").asText() + "/publish",
                Map.of("expectedVersion", 1)).status()).isEqualTo(400);

        var updated = admin.write("PATCH", papers + "/" + fixedId,
                Map.of("expectedVersion", 2, "draft", Map.of("name", "新稿", "durationMinutes", 60, "groups", groups)));
        assertThat(updated.status()).isEqualTo(200);
        String original = updated.data().path("paper").path("publishedId").asText();
        assertThat(student.get("/me/papers/" + fixedId).data().path("name").asText()).isEqualTo("练习");
        assertThat(admin.write("PATCH", papers + "/" + fixedId,
                Map.of("expectedVersion", 2, "draft", ordinary)).status()).isEqualTo(409);
        assertThat(admin.write("POST", papers + "/" + fixedId + "/publish", Map.of("expectedVersion", 3)).status())
                .isEqualTo(200);
        assertThat(student.get("/me/papers/" + fixedId).data().path("name").asText()).isEqualTo("新稿");
        assertThat(admin.get(papers + "/" + fixedId + "?versionId=" + original).data().path("content").path("name").asText())
                .isEqualTo("练习");
        assertThat(admin.write("DELETE", papers + "/" + fixedId, Map.of("expectedVersion", 4)).status()).isEqualTo(200);
        assertThat(student.get("/me/papers/" + fixedId).status()).isEqualTo(404);
    }

    @Test
    void rollsBackPublishedVersionIfUpdatingThePaperFails() throws Exception {
        var admin = new Browser();
        assertThat(admin.login("MainAdmin", PASSWORD).status()).isEqualTo(200);
        String subject = admin.write("POST", "/admin/subjects",
                Map.of("gradeId", GRADE, "name", "Rollback")).data().path("id").asText();
        String question = createShortQuestion(admin, subject, "2.00");
        var draft = Map.of("name", "事务测试", "durationMinutes", 10,
                "groups", java.util.List.of(Map.of("name", "组", "questionIds", java.util.List.of(question))));
        var created = admin.write("POST", "/admin/papers", Map.of("subjectId", subject, "type", "FIXED", "draft", draft));
        assertThat(created.status()).isEqualTo(201);
        String path = "/admin/papers/" + created.data().path("paper").path("id").asText();
        jdbc.execute("""
                CREATE FUNCTION test_reject_publish() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.published_id IS NOT NULL THEN RAISE EXCEPTION 'test publish failure'; END IF;
                    RETURN NEW; END $$
                """);
        try {
            jdbc.execute("CREATE TRIGGER test_reject_publish BEFORE UPDATE ON papers FOR EACH ROW EXECUTE FUNCTION test_reject_publish()");
            assertThat(admin.write("POST", path + "/publish", Map.of("expectedVersion", 1)).status()).isEqualTo(503);
            var afterFailure = admin.get(path).data();
            assertThat(afterFailure.path("content").path("publishedAt").isNull()).isTrue();
            assertThat(afterFailure.path("paper").path("publishedId").isNull()).isTrue();
            assertThat(afterFailure.path("paper").path("version").asInt()).isEqualTo(1);
        } finally {
            jdbc.execute("DROP FUNCTION test_reject_publish() CASCADE");
        }
        assertThat(admin.write("POST", path + "/publish", Map.of("expectedVersion", 1)).status()).isEqualTo(200);
    }

    private String createShortQuestion(Browser admin, String subject, String score) throws Exception {
        var created = admin.write("POST", "/admin/questions", Map.of("type", "SHORT", "score", score,
                "subjectId", subject, "difficulty", 3, "stemHtml", "题目", "answer", Map.of("text", "答案")));
        assertThat(created.status()).isEqualTo(201);
        return created.data().path("id").asText();
    }

    @Test
    void expiresIdleSessionsAndRevokesWebAndWechatOnPasswordChange() throws Exception {
        clock.override = Instant.parse("2026-11-10T00:00:00Z");
        var web = new Browser();
        var idle = new Browser();
        assertThat(web.write("POST", "/auth/register", Map.of("username", "PasswordStudent",
                "password", PASSWORD, "gradeId", GRADE)).status()).isEqualTo(201);
        assertThat(web.login("PasswordStudent", PASSWORD).status()).isEqualTo(200);
        assertThat(idle.login("PasswordStudent", PASSWORD).status()).isEqualTo(200);
        subject("password-bind", "password-account");
        var binding = wxBind(new Browser(), "password-bind", "PasswordStudent");
        assertThat(binding.status()).isEqualTo(200);
        var token = binding.data().path("token").asText();
        clock.override = Instant.parse("2026-11-10T00:29:59Z");
        assertThat(web.get("/me").status()).isEqualTo(200);
        clock.override = Instant.parse("2026-11-10T00:30:00Z");
        assertThat(idle.get("/me").status()).isEqualTo(401);
        assertThat(bearer("GET", "/me", token).status()).isEqualTo(200);
        String newPassword = "Different-test-secret-9217";
        assertThat(web.write("POST", "/me/password", Map.of("currentPassword", PASSWORD,
                "newPassword", newPassword)).status()).isEqualTo(200);
        assertThat(web.get("/me").status()).isEqualTo(401);
        assertThat(bearer("GET", "/me", token).status()).isEqualTo(401);
        assertThat(web.login("PasswordStudent", PASSWORD).status()).isEqualTo(401);
        assertThat(web.login("PasswordStudent", newPassword).status()).isEqualTo(200);
    }

    @Test
    void freezesAssignmentPapersAndEligibilityAndRollsBackIfOnePaperIsAlreadyBound() throws Exception {
        var admin = new Browser();
        assertThat(admin.login("MainAdmin", PASSWORD).status()).isEqualTo(200);
        UUID otherGrade = UUID.randomUUID();
        jdbc.update("INSERT INTO grades(id, name) VALUES (?, '其他年级')", otherGrade);
        var target = new Browser();
        var member = target.write("POST", "/auth/register", Map.of("username", "TargetMember",
                "password", PASSWORD, "gradeId", GRADE)).data();
        assertThat(target.login("TargetMember", PASSWORD).status()).isEqualTo(200);
        var outsider = new Browser();
        assertThat(outsider.write("POST", "/auth/register", Map.of("username", "OutsideMember",
                "password", PASSWORD, "gradeId", otherGrade)).status()).isEqualTo(201);
        assertThat(outsider.login("OutsideMember", PASSWORD).status()).isEqualTo(200);
        String subject = admin.write("POST", "/admin/subjects",
                Map.of("gradeId", GRADE, "name", "Task subject")).data().path("id").asText();
        String unrelated = admin.write("POST", "/admin/subjects",
                Map.of("gradeId", otherGrade, "name", "Other task subject")).data().path("id").asText();
        String question = createShortQuestion(admin, subject, "1.50");
        String foreignQuestion = createShortQuestion(admin, unrelated, "2.50");
        String first = createPublishedPaper(admin, subject, question, "TASK", "卷甲");
        String second = createPublishedPaper(admin, subject, question, "TASK", "卷乙");
        String free = createPublishedPaper(admin, subject, question, "TASK", "卷丙");
        String ordinary = createPublishedPaper(admin, subject, question, "FIXED", "固定卷");
        String foreign = createPublishedPaper(admin, unrelated, foreignQuestion, "TASK", "其他年级卷");
        String tasks = "/admin/tasks";
        var request = Map.of("name", "二卷任务", "gradeId", GRADE, "paperIds", java.util.List.of(first, second));
        var draft = admin.write("POST", tasks, request);
        assertThat(draft.status()).isEqualTo(201);
        String id = draft.data().path("id").asText();
        assertThat(target.get("/me/tasks/" + id).status()).isEqualTo(404);
        var published = admin.write("POST", tasks + "/" + id + "/publish", Map.of("expectedVersion", 1));
        assertThat(published.status()).isEqualTo(200);
        assertThat(published.data().path("recipientCount").asInt()).isEqualTo(1);
        assertThat(published.data().path("papers").size()).isEqualTo(2);
        assertThat(published.data().path("papers").get(0).path("paperId").asText()).isEqualTo(first);
        assertThat(target.get("/me/tasks/" + id).data().path("papers").get(1).path("name").asText()).isEqualTo("卷乙");
        subject("task-member-code", "task-member");
        var token = wxBind(new Browser(), "task-member-code", "TargetMember").data().path("token").asText();
        assertThat(bearer("GET", "/me/tasks/" + id, token).data().path("papers").get(1).path("status").asText())
                .isEqualTo("NOT_STARTED");
        assertThat(outsider.get("/me/tasks/" + id).status()).isEqualTo(404);
        assertThat(admin.write("PATCH", tasks + "/" + id, Map.of("expectedVersion", 2, "draft", request)).status())
                .isEqualTo(409);

        var late = new Browser();
        assertThat(late.write("POST", "/auth/register", Map.of("username", "LateMember",
                "password", PASSWORD, "gradeId", GRADE)).status()).isEqualTo(201);
        assertThat(late.login("LateMember", PASSWORD).status()).isEqualTo(200);
        assertThat(late.get("/me/tasks/" + id).status()).isEqualTo(404);
        String memberPath = USERS + "/" + member.path("id").asText();
        assertThat(admin.write("PATCH", memberPath, Map.of("username", "TargetMember", "role", "STUDENT",
                "gradeId", otherGrade, "expectedVersion", 1)).status()).isEqualTo(200);
        assertThat(target.get("/me/tasks/" + id).status()).isEqualTo(200);
        assertThat(admin.write("POST", memberPath + "/disable", Map.of("expectedVersion", 2)).status()).isEqualTo(200);
        assertThat(target.get("/me/tasks/" + id).status()).isEqualTo(401);
        assertThat(bearer("GET", "/me/tasks/" + id, token).status()).isEqualTo(401);

        assertThat(admin.write("POST", tasks, Map.of("name", "重复卷", "gradeId", GRADE,
                "paperIds", java.util.List.of(first, first))).status()).isEqualTo(400);
        for (String wrong : java.util.List.of(ordinary, foreign, second)) {
            var bad = admin.write("POST", tasks, Map.of("name", "无效发布", "gradeId", GRADE,
                    "paperIds", java.util.List.of(free, wrong)));
            String badId = bad.data().path("id").asText();
            assertThat(admin.write("POST", tasks + "/" + badId + "/publish", Map.of("expectedVersion", 1)).status())
                    .isEqualTo(wrong.equals(second) ? 409 : 400);
            var current = admin.get(tasks + "/" + badId).data();
            assertThat(current.path("task").path("state").asText()).isEqualTo("DRAFT");
            assertThat(current.path("papers").size()).isZero();
            assertThat(current.path("recipientCount").asInt()).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_papers", Integer.class)).isEqualTo(2);
        assertThat(admin.write("DELETE", tasks + "/" + id, Map.of("expectedVersion", 2)).status()).isEqualTo(200);
        assertThat(admin.get(tasks + "/" + id).data().path("task").path("state").asText()).isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_papers", Integer.class)).isEqualTo(2);
    }

    private String createPublishedPaper(Browser admin, String subject, String question, String type, String name) throws Exception {
        var draft = Map.of("name", name, "durationMinutes", 20,
                "groups", java.util.List.of(Map.of("name", "题目", "questionIds", java.util.List.of(question))));
        var created = admin.write("POST", "/admin/papers", Map.of("subjectId", subject, "type", type, "draft", draft));
        assertThat(created.status()).isEqualTo(201);
        String id = created.data().path("paper").path("id").asText();
        assertThat(admin.write("POST", "/admin/papers/" + id + "/publish", Map.of("expectedVersion", 1)).status())
                .isEqualTo(200);
        return id;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean @Primary AdjustableClock adjustableClock() { return new AdjustableClock(); }
    }

    static final class AdjustableClock extends Clock {
        volatile Instant override;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return override == null ? Instant.now() : override; }
    }

    private void subject(String code, String openId) {
        when(wechat.exchange(code)).thenReturn(new Subject("fixture-app", openId));
    }

    private Result wxBind(Browser browser, String code, String name) throws Exception {
        return browser.request("POST", "/auth/wechat/bind",
                Map.of("code", code, "username", name, "password", PASSWORD), false);
    }

    private Result bearer(String method, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer " + token).method(method, HttpRequest.BodyPublishers.noBody()).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return new Result(response, json.readTree(response.body()).path("data"));
    }

    private record Result(HttpResponse<String> response, JsonNode data) {
        int status() { return response.statusCode(); }
    }

    private class Browser {
        final HttpClient http = HttpClient.newHttpClient();
        String session;

        Result login(String username, String password) throws Exception {
            return write("POST", "/auth/login", Map.of("username", username, "password", password));
        }

        Result get(String path) throws Exception { return request("GET", path, null, false); }

        Result write(String method, String path, Object body) throws Exception {
            return request(method, path, body, true);
        }

        HttpRequest prepare(String method, String path, Object body, boolean withCsrf) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
            String cookies = session;
            if (withCsrf) {
                var csrf = get("/auth/csrf");
                var csrfCookie = csrf.response().headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
                cookies = cookies == null ? csrfCookie : cookies + "; " + csrfCookie;
                request.header(csrf.data().path("headerName").asText(), csrf.data().path("token").asText());
            }
            if (cookies != null) request.header("Cookie", cookies);
            if (body == null) return request.method(method, HttpRequest.BodyPublishers.noBody()).build();
            return request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        }

        Result request(String method, String path, Object body, boolean withCsrf) throws Exception {
            var response = http.send(prepare(method, path, body, withCsrf), HttpResponse.BodyHandlers.ofString());
            response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("__Host-exam_session="))
                    .forEach(value -> session = value.split(";", 2)[0]);
            return new Result(response, json.readTree(response.body()).path("data"));
        }

        Result upload(String name, byte[] bytes) throws Exception {
            var csrf = get("/auth/csrf");
            var cookie = csrf.response().headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            String boundary = "upload-boundary";
            var body = HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\n"
                            + "Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
                            + "Content-Type: image/png\r\n\r\n"),
                    HttpRequest.BodyPublishers.ofByteArray(bytes),
                    HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/attachments"))
                    .header("Cookie", session + "; " + cookie)
                    .header(csrf.data().path("headerName").asText(), csrf.data().path("token").asText())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(body).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Result(response, json.readTree(response.body()).path("data"));
        }
    }
}
