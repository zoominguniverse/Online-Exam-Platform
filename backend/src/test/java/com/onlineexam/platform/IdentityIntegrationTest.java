package com.onlineexam.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.onlineexam.platform.domain.ApiException;
import com.onlineexam.platform.service.AttachmentService;
import com.onlineexam.platform.service.IdentityService;
import com.onlineexam.platform.service.AttemptLifecycle;
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
import javax.sql.DataSource;
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

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.flyway.enabled=true", "exam.deadlines.enabled=false"})
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
    @Autowired DataSource dataSource;
    @Autowired AttemptLifecycle lifecycle;
    @Autowired com.onlineexam.platform.service.ObservabilityService observability;
    @Value("${PRIVATE_STORAGE_ROOT}") String privateRoot;
    @LocalServerPort int port;

    @BeforeEach
    void prepare() {
        clock.override = null;
        jdbc.execute("""
                TRUNCATE user_daily_activity, audit_events, deleted_account_ledger, message_recipients, messages, profiles,
                    operation_keys, gradings, mistakes, attempt_submit_keys, attempt_results, attempt_answers, attempt_start_keys, attempt_items, attempts,
                    task_recipients, task_papers, tasks, attachment_links, attachments,
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
        assertThat(student.get("/me/papers?type=TASK").data().path("total").asInt()).isZero();
        assertThat(student.get("/me/papers?type=TIMED&status=UPCOMING").data().path("items").get(0)
                .path("id").asText()).isEqualTo(timedId);
        assertThat(student.get("/me/papers/" + timedId).data().path("status").asText()).isEqualTo("UPCOMING");
        clock.override = Instant.parse("2026-11-10T00:01:00Z");
        assertThat(student.get("/me/papers/" + timedId).data().path("status").asText()).isEqualTo("OPEN");
        assertThat(student.get("/me/papers?type=TIMED&status=OPEN").data().path("total").asInt()).isEqualTo(1);
        clock.override = Instant.parse("2026-11-10T00:02:00Z");
        assertThat(student.get("/me/papers/" + timedId).data().path("status").asText()).isEqualTo("CLOSED");
        assertThat(student.get("/me/papers?type=TIMED&status=CLOSED").data().path("total").asInt()).isEqualTo(1);
        assertThat(student.get("/me/papers?type=TIMED&status=OPEN").data().path("total").asInt()).isZero();
        assertThat(student.get("/me/papers?status=OPEN&type=FIXED").data().path("total").asInt()).isEqualTo(1);
        assertThat(student.get("/me/papers/" + fixedId).data().has("items")).isFalse();
        UUID otherGrade = UUID.randomUUID();
        jdbc.update("INSERT INTO grades(id, name) VALUES (?, '其他年级')", otherGrade);
        var other = new Browser();
        assertThat(other.write("POST", "/auth/register",
                Map.of("gradeId", otherGrade, "username", "OtherGrade", "password", PASSWORD)).status()).isEqualTo(201);
        assertThat(other.login("OtherGrade", PASSWORD).status()).isEqualTo(200);
        assertThat(other.get("/me/papers/" + fixedId).status()).isEqualTo(404);
        assertThat(other.get("/me/papers?type=FIXED").data().path("total").asInt()).isZero();

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
    void startsOnlyEligibleOrdinaryPapersAndFreezesSafeSnapshots() throws Exception {
        clock.override = Instant.parse("2026-11-10T00:00:00Z");
        var admin = new Browser();
        assertThat(admin.login("MainAdmin", PASSWORD).status()).isEqualTo(200);
        var student = new Browser();
        assertThat(student.write("POST", "/auth/register", Map.of("username", "AttemptStudent",
                "password", PASSWORD, "gradeId", GRADE)).status()).isEqualTo(201);
        assertThat(student.login("AttemptStudent", PASSWORD).status()).isEqualTo(200);
        UUID otherGrade = UUID.randomUUID();
        jdbc.update("INSERT INTO grades(id, name) VALUES (?, '异年级')", otherGrade);
        var outsider = new Browser();
        assertThat(outsider.write("POST", "/auth/register", Map.of("username", "AttemptOutsider",
                "password", PASSWORD, "gradeId", otherGrade)).status()).isEqualTo(201);
        assertThat(outsider.login("AttemptOutsider", PASSWORD).status()).isEqualTo(200);
        String subject = admin.write("POST", "/admin/subjects", Map.of("gradeId", GRADE,
                "name", "答卷测试学科")).data().path("id").asText();
        String question = createShortQuestion(admin, subject, "2.50");
        var group = java.util.List.of(Map.of("name", "题组", "questionIds", java.util.List.of(question)));
        var draft = Map.of("name", "未发布", "durationMinutes", 20, "groups", group);
        String unpublished = admin.write("POST", "/admin/papers", Map.of("subjectId", subject,
                "type", "FIXED", "draft", draft)).data().path("paper").path("id").asText();
        String fixed = createPublishedPaper(admin, subject, question, "FIXED", "练习卷");
        String task = createPublishedPaper(admin, subject, question, "TASK", "任务卷");
        var timedDraft = Map.of("name", "时段卷", "durationMinutes", 20, "groups", group,
                "opensAt", "2026-11-10T00:01:00Z", "closesAt", "2026-11-10T00:02:00Z");
        var created = admin.write("POST", "/admin/papers", Map.of("subjectId", subject,
                "type", "TIMED", "draft", timedDraft));
        String timed = created.data().path("paper").path("id").asText();
        assertThat(admin.write("POST", "/admin/papers/" + timed + "/publish",
                Map.of("expectedVersion", 1)).status()).isEqualTo(200);

        assertThat(new Browser().write("POST", "/me/papers/" + fixed + "/attempts", null).status()).isEqualTo(401);
        assertThat(admin.write("POST", "/me/papers/" + fixed + "/attempts", null).status()).isEqualTo(403);
        for (String id : java.util.List.of(unpublished, task)) {
            assertThat(student.start(id, null).status()).isEqualTo(404);
        }
        assertThat(outsider.start(fixed, null).status()).isEqualTo(404);
        assertThat(student.start(timed, null).status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempts", Integer.class)).isZero();

        var first = student.start(fixed, "fixed-key");
        assertThat(first.status()).isEqualTo(201);
        String firstId = first.data().path("id").asText();
        assertThat(first.data().path("deadlineAt").asText()).isEqualTo("2026-11-10T00:20:00Z");
        assertThat(first.data().path("serverNow").asText()).isEqualTo("2026-11-10T00:00:00Z");
        assertThat(first.data().path("questions").get(0).path("questionVersion").asInt()).isEqualTo(1);
        assertThat(first.data().path("questions").get(0).path("score").asText()).isEqualTo("2.50");
        assertThat(first.response().body()).doesNotContain("答案", "correctAnswer", "explanationHtml");
        assertThat(student.start(fixed, "fixed-key").data().path("id").asText()).isEqualTo(firstId);
        assertThat(student.start(timed, "fixed-key").status()).isEqualTo(409);
        assertThat(student.get("/me/attempts/" + firstId).data().path("id").asText()).isEqualTo(firstId);
        assertThat(outsider.get("/me/attempts/" + firstId).status()).isEqualTo(404);
        assertThat(student.start(fixed, "another-key").data().path("id").asText()).isNotEqualTo(firstId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempts", Integer.class)).isEqualTo(2);

        var secondSession = new Browser();
        assertThat(secondSession.login("AttemptStudent", PASSWORD).status()).isEqualTo(200);
        var concurrentWeb = CompletableFuture.supplyAsync(() -> {
            try { return student.start(fixed, "concurrent-key"); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        var concurrentOther = CompletableFuture.supplyAsync(() -> {
            try { return secondSession.start(fixed, "concurrent-key"); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        assertThat(concurrentWeb.join().status()).isEqualTo(201);
        assertThat(concurrentOther.join().status()).isEqualTo(201);
        assertThat(concurrentWeb.join().data().path("id").asText())
                .isEqualTo(concurrentOther.join().data().path("id").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempts", Integer.class)).isEqualTo(3);

        var revised = Map.of("type", "SHORT", "score", "3.00", "subjectId", subject,
                "difficulty", 3, "stemHtml", "新题目", "answer", Map.of("text", "新答案"));
        assertThat(admin.write("PATCH", "/admin/questions/" + question,
                Map.of("expectedVersion", 1, "question", revised)).status()).isEqualTo(200);
        var restored = student.get("/me/attempts/" + firstId);
        assertThat(restored.data().path("questions").get(0).path("stemHtml").asText()).isEqualTo("题目");
        assertThat(restored.data().path("questions").get(0).path("score").asText()).isEqualTo("2.50");
        assertThat(restored.response().body()).doesNotContain("答案", "correctAnswer", "explanationHtml");

        clock.override = Instant.parse("2026-11-10T00:01:00Z");
        var opening = student.start(timed, "opening");
        assertThat(opening.status()).isEqualTo(201);
        assertThat(opening.data().path("deadlineAt").asText()).isEqualTo("2026-11-10T00:02:00Z");
        clock.override = Instant.parse("2026-11-10T00:01:59.999Z");
        assertThat(student.start(timed, "before-close").status()).isEqualTo(201);
        clock.override = Instant.parse("2026-11-10T00:02:00Z");
        assertThat(student.start(timed, "at-close").status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempts", Integer.class)).isEqualTo(5);
        assertThat(admin.write("DELETE", "/admin/papers/" + fixed,
                Map.of("expectedVersion", 2)).status()).isEqualTo(200);
        assertThat(student.start(fixed, "after-archive").status()).isEqualTo(404);
        assertThat(student.get("/me/attempts/" + firstId).status()).isEqualTo(200);
    }

    @Test
    void startsFrozenTaskPapersOnceAcrossIndependentWebSessions() throws Exception {
        clock.override = Instant.parse("2026-11-10T00:00:00Z");
        var admin = new Browser();
        assertThat(admin.login("MainAdmin", PASSWORD).status()).isEqualTo(200);
        var member = new Browser();
        var memberAccount = member.write("POST", "/auth/register", Map.of("username", "TaskStarter",
                "password", PASSWORD, "gradeId", GRADE)).data();
        assertThat(member.login("TaskStarter", PASSWORD).status()).isEqualTo(200);
        var other = new Browser();
        assertThat(other.write("POST", "/auth/register", Map.of("username", "TaskOther",
                "password", PASSWORD, "gradeId", GRADE)).status()).isEqualTo(201);
        assertThat(other.login("TaskOther", PASSWORD).status()).isEqualTo(200);
        String subjectId = admin.write("POST", "/admin/subjects", Map.of("gradeId", GRADE,
                "name", "任务开始学科")).data().path("id").asText();
        String questionId = createShortQuestion(admin, subjectId, "2.50");
        String paperA = createPublishedPaper(admin, subjectId, questionId, "TASK", "任务甲");
        String paperB = createPublishedPaper(admin, subjectId, questionId, "TASK", "任务乙");
        String ordinary = createPublishedPaper(admin, subjectId, questionId, "FIXED", "普通练习");
        var form = Map.of("name", "双卷任务", "gradeId", GRADE,
                "paperIds", java.util.List.of(paperA, paperB));
        String taskId = admin.write("POST", "/admin/tasks", form).data().path("id").asText();
        assertThat(member.startTask(taskId, paperA, "before-publish").status()).isEqualTo(404);
        var taskPublished = admin.write("POST", "/admin/tasks/" + taskId + "/publish",
                Map.of("expectedVersion", 1));
        assertThat(taskPublished.status()).isEqualTo(200);
        String frozenB = taskPublished.data().path("papers").get(1).path("paperVersionId").asText();
        var revisedPaper = Map.of("name", "新版任务乙", "durationMinutes", 30,
                "groups", java.util.List.of(Map.of("name", "题组", "questionIds", java.util.List.of(questionId))));
        assertThat(admin.write("PATCH", "/admin/papers/" + paperB,
                Map.of("expectedVersion", 2, "draft", revisedPaper)).status()).isEqualTo(200);
        assertThat(admin.write("POST", "/admin/papers/" + paperB + "/publish",
                Map.of("expectedVersion", 3)).status()).isEqualTo(200);
        var late = new Browser();
        assertThat(late.write("POST", "/auth/register", Map.of("username", "LateStarter",
                "password", PASSWORD, "gradeId", GRADE)).status()).isEqualTo(201);
        assertThat(late.login("LateStarter", PASSWORD).status()).isEqualTo(200);
        assertThat(late.startTask(taskId, paperA, "late").status()).isEqualTo(404);
        assertThat(member.startTask(taskId, ordinary, "wrong-paper").status()).isEqualTo(404);
        assertThat(member.startTask(UUID.randomUUID().toString(), paperA, "wrong-task").status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempt_start_keys", Integer.class)).isZero();

        var secondSession = new Browser();
        assertThat(secondSession.login("TaskStarter", PASSWORD).status()).isEqualTo(200);
        var gate = new java.util.concurrent.CountDownLatch(1);
        var web = CompletableFuture.supplyAsync(() -> {
            try { gate.await(); return member.startTask(taskId, paperA, "same-task-key"); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        var parallelWeb = CompletableFuture.supplyAsync(() -> {
            try { gate.await(); return secondSession.startTask(taskId, paperA, "same-task-key"); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        gate.countDown();
        assertThat(web.join().status()).isEqualTo(201);
        assertThat(parallelWeb.join().status()).isEqualTo(201);
        String firstId = web.join().data().path("id").asText();
        assertThat(parallelWeb.join().data().path("id").asText()).isEqualTo(firstId);
        assertThat(member.startTask(UUID.randomUUID().toString(), paperA, "same-task-key").status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempts WHERE paper_id = ?", Integer.class,
                UUID.fromString(paperA))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempt_items WHERE attempt_id = ?", Integer.class,
                UUID.fromString(firstId))).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO attempts(id, owner_id, task_id, paper_id, paper_version_id, started_at, deadline_at)
                SELECT ?, owner_id, task_id, paper_id, paper_version_id, started_at, deadline_at
                FROM attempts WHERE id = ?
                """, UUID.randomUUID(), UUID.fromString(firstId)))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempts WHERE paper_id = ?", Integer.class,
                UUID.fromString(paperA))).isEqualTo(1);
        String deadline = web.join().data().path("deadlineAt").asText();
        assertThat(web.join().response().body()).doesNotContain("correctAnswer", "explanationHtml", "答案");

        UUID changedGrade = UUID.randomUUID();
        jdbc.update("INSERT INTO grades(id, name) VALUES (?, '转入年级')", changedGrade);
        assertThat(admin.write("PATCH", USERS + "/" + memberAccount.path("id").asText(),
                Map.of("username", "TaskStarter", "role", "STUDENT", "gradeId", changedGrade,
                        "expectedVersion", 1)).status()).isEqualTo(200);

        var secondGate = new java.util.concurrent.CountDownLatch(1);
        var secondWeb = CompletableFuture.supplyAsync(() -> {
            try { secondGate.await(); return member.startTask(taskId, paperB, "different-web"); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        var secondSessionRequest = CompletableFuture.supplyAsync(() -> {
            try { secondGate.await(); return secondSession.startTask(taskId, paperB, "different-session"); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        secondGate.countDown();
        assertThat(secondWeb.join().status()).isEqualTo(201);
        assertThat(secondSessionRequest.join().status()).isEqualTo(201);
        String secondId = secondWeb.join().data().path("id").asText();
        assertThat(secondSessionRequest.join().data().path("id").asText()).isEqualTo(secondId);
        assertThat(secondWeb.join().data().path("paperVersionId").asText()).isEqualTo(frozenB);
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempts WHERE owner_id = ?", Integer.class,
                UUID.fromString(memberAccount.path("id").asText()))).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempt_items WHERE attempt_id = ?", Integer.class,
                UUID.fromString(secondId))).isEqualTo(1);

        var otherStart = other.startTask(taskId, paperA, "other-student");
        assertThat(otherStart.status()).isEqualTo(201);
        assertThat(otherStart.data().path("id").asText()).isNotEqualTo(firstId);
        assertThat(other.get("/me/attempts/" + firstId).status()).isEqualTo(404);
        assertThat(admin.write("DELETE", "/admin/papers/" + paperB,
                Map.of("expectedVersion", 4)).status()).isEqualTo(200);
        assertThat(other.startTask(taskId, paperB, "paper-archived").status()).isEqualTo(404);
        assertThat(member.startTask(taskId, paperB, "paper-archived-resume").data().path("id").asText())
                .isEqualTo(secondId);
        var edited = Map.of("type", "SHORT", "score", "3.00", "subjectId", subjectId,
                "difficulty", 3, "stemHtml", "修改后题目", "answer", Map.of("text", "修改后答案"));
        assertThat(admin.write("PATCH", "/admin/questions/" + questionId,
                Map.of("expectedVersion", 1, "question", edited)).status()).isEqualTo(200);
        clock.override = Instant.parse("2026-11-10T00:05:00Z");
        var resumed = secondSession.get("/me/attempts/" + firstId);
        assertThat(resumed.status()).isEqualTo(200);
        assertThat(resumed.data().path("deadlineAt").asText()).isEqualTo(deadline);
        assertThat(resumed.data().path("questions").get(0).path("stemHtml").asText()).isEqualTo("题目");
        assertThat(member.startTask(taskId, paperA, "retry-after-edit").data().path("id").asText()).isEqualTo(firstId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempt_items WHERE attempt_id = ?", Integer.class,
                UUID.fromString(firstId))).isEqualTo(1);
        assertThat(admin.write("DELETE", "/admin/tasks/" + taskId,
                Map.of("expectedVersion", 2)).status()).isEqualTo(200);
        assertThat(member.startTask(taskId, paperA, "archived-resume").data().path("id").asText()).isEqualTo(firstId);
        assertThat(other.startTask(taskId, paperB, "task-archived-new").status()).isEqualTo(404);
        assertThat(late.startTask(taskId, paperB, "archived-new").status()).isEqualTo(404);
        assertThat(other.start(ordinary, "ordinary-1").status()).isEqualTo(201);
        assertThat(other.start(ordinary, "ordinary-2").status()).isEqualTo(201);
    }

    @Test
    void savesWholeAnswerSetWithVersionAndDeadlineChecks() throws Exception {
        clock.override = Instant.parse("2026-11-10T00:00:00Z");
        var admin = new Browser();
        assertThat(admin.login("MainAdmin", PASSWORD).status()).isEqualTo(200);
        var student = new Browser();
        assertThat(student.write("POST", "/auth/register", Map.of("username", "AnswerStudent",
                "password", PASSWORD, "gradeId", GRADE)).status()).isEqualTo(201);
        assertThat(student.login("AnswerStudent", PASSWORD).status()).isEqualTo(200);
        var other = new Browser();
        assertThat(other.write("POST", "/auth/register", Map.of("username", "AnswerOther",
                "password", PASSWORD, "gradeId", GRADE)).status()).isEqualTo(201);
        assertThat(other.login("AnswerOther", PASSWORD).status()).isEqualTo(200);
        String subjectId = admin.write("POST", "/admin/subjects", Map.of("gradeId", GRADE,
                "name", "答案测试学科")).data().path("id").asText();
        var options = java.util.List.of(Map.of("id", "A", "text", "甲"), Map.of("id", "B", "text", "乙"));
        var base = Map.<String, Object>of("subjectId", subjectId, "difficulty", 3,
                "score", "1.00", "stemHtml", "题目");
        var variants = java.util.List.of(
                Map.of("type", "SINGLE", "options", options, "answer", Map.of("optionIds", java.util.List.of("A"))),
                Map.of("type", "MULTIPLE", "options", options, "answer", Map.of("optionIds", java.util.List.of("A", "B"))),
                Map.of("type", "TRUE_FALSE", "options", java.util.List.of(), "answer", Map.of("optionIds", java.util.List.of("true"))),
                Map.of("type", "GAP", "options", java.util.List.of(), "stemHtml", "<span data-gap=\"1\">___</span>",
                        "answer", Map.of("gaps", java.util.List.of("参考"))),
                Map.of("type", "SHORT", "options", java.util.List.of(), "answer", Map.of("text", "参考")));
        var questionIds = new java.util.ArrayList<String>();
        for (var variant : variants) {
            var draft = new java.util.HashMap<String, Object>(base);
            draft.putAll(variant);
            var created = admin.write("POST", "/admin/questions", draft);
            assertThat(created.status()).isEqualTo(201);
            questionIds.add(created.data().path("id").asText());
        }
        var paperDraft = Map.of("name", "五题型卷", "durationMinutes", 20,
                "groups", java.util.List.of(Map.of("name", "题组", "questionIds", questionIds)));
        String paper = admin.write("POST", "/admin/papers", Map.of("subjectId", subjectId,
                "type", "FIXED", "draft", paperDraft)).data().path("paper").path("id").asText();
        assertThat(admin.write("POST", "/admin/papers/" + paper + "/publish",
                Map.of("expectedVersion", 1)).status()).isEqualTo(200);
        var started = student.start(paper, "answers-start");
        assertThat(started.status()).isEqualTo(201);
        String attempt = started.data().path("id").asText();
        String path = "/me/attempts/" + attempt + "/answers";
        assertThat(new Browser().write("PUT", path, Map.of("expectedVersion", 1,
                "answerItems", java.util.List.of())).status()).isEqualTo(401);
        assertThat(admin.write("PUT", path, Map.of("expectedVersion", 1,
                "answerItems", java.util.List.of())).status()).isEqualTo(403);
        var answerItems = java.util.List.of(
                Map.of("questionId", questionIds.get(0), "answer", Map.of("optionId", "B")),
                Map.of("questionId", questionIds.get(1), "answer", Map.of("optionIds", java.util.List.of("B", "A"))),
                Map.of("questionId", questionIds.get(2), "answer", Map.of("optionId", "false")),
                Map.of("questionId", questionIds.get(3), "answer", Map.of("texts", java.util.List.of("填写"))),
                Map.of("questionId", questionIds.get(4), "answer", Map.of("text", "作答")));
        assertThat(other.write("PUT", path, Map.of("expectedVersion", 1, "answerItems", answerItems)).status())
                .isEqualTo(404);
        var saved = student.write("PUT", path, Map.of("expectedVersion", 1, "answerItems", answerItems));
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.data().path("version").asInt()).isEqualTo(2);
        assertThat(saved.data().path("savedAnswers").size()).isEqualTo(5);
        assertThat(saved.response().body()).doesNotContain("correctAnswer", "explanationHtml", "参考");
        var restoringWeb = new Browser();
        assertThat(restoringWeb.login("AnswerStudent", PASSWORD).status()).isEqualTo(200);
        var restored = restoringWeb.get("/me/attempts/" + attempt);
        assertThat(restored.data().path("savedAnswers").size()).isEqualTo(5);
        assertThat(restored.data().path("version").asInt()).isEqualTo(2);
        assertThat(restored.data().path("deadlineAt")).isEqualTo(started.data().path("deadlineAt"));
        assertThat(student.write("PUT", path, Map.of("expectedVersion", 1,
                "answerItems", java.util.List.of())).status()).isEqualTo(409);
        var invalid = java.util.List.of(answerItems.get(0),
                Map.of("questionId", questionIds.get(1), "answer", Map.of("optionIds", java.util.List.of("X"))));
        assertThat(student.write("PUT", path, Map.of("expectedVersion", 2, "answerItems", invalid)).status())
                .isEqualTo(400);
        for (var rejected : java.util.List.of(
                java.util.List.of(answerItems.get(0), answerItems.get(0)),
                java.util.List.of(Map.of("questionId", UUID.randomUUID().toString(), "answer", Map.of("text", "外题"))),
                java.util.List.of(Map.of("questionId", questionIds.get(0), "answer", Map.of("text", "错题型"))),
                java.util.List.of(Map.of("questionId", questionIds.get(4), "answer", Map.of("text", "x".repeat(10001)))))) {
            assertThat(student.write("PUT", path, Map.of("expectedVersion", 2, "answerItems", rejected)).status())
                    .isEqualTo(400);
        }
        assertThat(student.get("/me/attempts/" + attempt).data().path("version").asInt()).isEqualTo(2);
        var cleared = student.write("PUT", path, Map.of("expectedVersion", 2,
                "answerItems", java.util.List.of(answerItems.get(0))));
        assertThat(cleared.status()).isEqualTo(200);
        assertThat(cleared.data().path("savedAnswers").size()).isEqualTo(1);
        clock.override = Instant.parse("2026-11-10T00:19:59.999Z");
        var clearedItem = new java.util.HashMap<String, Object>();
        clearedItem.put("questionId", questionIds.get(0));
        clearedItem.put("answer", null);
        var secondSession = new Browser();
        assertThat(secondSession.login("AnswerStudent", PASSWORD).status()).isEqualTo(200);
        var gate = new java.util.concurrent.CountDownLatch(1);
        var firstWrite = CompletableFuture.supplyAsync(() -> {
            try { gate.await(); return student.write("PUT", path,
                    Map.of("expectedVersion", 3, "answerItems", java.util.List.of(clearedItem))); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        var secondWrite = CompletableFuture.supplyAsync(() -> {
            try { gate.await(); return secondSession.write("PUT", path,
                    Map.of("expectedVersion", 3, "answerItems", answerItems)); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        gate.countDown();
        assertThat(java.util.List.of(firstWrite.join().status(), secondWrite.join().status()))
                .containsExactlyInAnyOrder(200, 409);
        var winner = student.get("/me/attempts/" + attempt).data();
        assertThat(winner.path("version").asInt()).isEqualTo(4);
        assertThat(winner.path("savedAnswers").size()).isIn(0, 5);
        try (var blocking = dataSource.getConnection()) {
            blocking.setAutoCommit(false);
            try (var lock = blocking.prepareStatement("UPDATE attempts SET version = version WHERE id = ?")) {
                lock.setObject(1, UUID.fromString(attempt));
                lock.executeUpdate();
            }
            var waitingSave = CompletableFuture.supplyAsync(() -> {
                try { return student.write("PUT", path, Map.of("expectedVersion", 4,
                        "answerItems", java.util.List.of())); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            boolean waiting = false;
            for (int retry = 0; retry < 80 && !waiting; retry++) {
                waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                        + "AND query LIKE '%FROM attempts WHERE id =%'", Integer.class) > 0;
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            clock.override = Instant.parse("2026-11-10T00:20:00Z");
            blocking.commit();
            assertThat(waitingSave.join().status()).isEqualTo(409);
        }
        assertThat(student.get("/me/attempts/" + attempt).data().path("savedAnswers").size())
                .isEqualTo(winner.path("savedAnswers").size());
    }

    @Test
    void expiresIdleSessionsAndRevokesAllWebSessionsOnPasswordChange() throws Exception {
        clock.override = Instant.parse("2026-11-10T00:00:00Z");
        var web = new Browser();
        var idle = new Browser();
        assertThat(web.write("POST", "/auth/register", Map.of("username", "PasswordStudent",
                "password", PASSWORD, "gradeId", GRADE)).status()).isEqualTo(201);
        assertThat(web.login("PasswordStudent", PASSWORD).status()).isEqualTo(200);
        assertThat(idle.login("PasswordStudent", PASSWORD).status()).isEqualTo(200);
        var activeSession = new Browser();
        assertThat(activeSession.login("PasswordStudent", PASSWORD).status()).isEqualTo(200);
        clock.override = Instant.parse("2026-11-10T00:29:59Z");
        assertThat(web.get("/me").status()).isEqualTo(200);
        assertThat(activeSession.get("/me").status()).isEqualTo(200);
        clock.override = Instant.parse("2026-11-10T00:30:00Z");
        assertThat(idle.get("/me").status()).isEqualTo(401);
        assertThat(activeSession.get("/me").status()).isEqualTo(200);
        String newPassword = "Different-test-secret-9217";
        assertThat(web.write("POST", "/me/password", Map.of("currentPassword", PASSWORD,
                "newPassword", newPassword)).status()).isEqualTo(200);
        assertThat(web.get("/me").status()).isEqualTo(401);
        assertThat(activeSession.get("/me").status()).isEqualTo(401);
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
        var secondMember = new Browser();
        assertThat(secondMember.login("TargetMember", PASSWORD).status()).isEqualTo(200);
        assertThat(secondMember.get("/me/tasks/" + id).data().path("papers").get(1).path("status").asText())
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
        assertThat(secondMember.get("/me/tasks/" + id).status()).isEqualTo(401);

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

    @Test
    void submitsFinalAnswersAtomicallyAndRetriesTheFrozenResult() throws Exception {
        var fixture = freezingFixture(true);
        var student = fixture.student();
        String id = fixture.id();
        String path = "/me/attempts/" + id;
        var savedItems = java.util.List.of(answer(fixture, 4, Map.of("text", "旧草稿")));
        assertThat(student.write("PUT", path + "/answers", Map.of("expectedVersion", 1, "answerItems", savedItems)).status())
                .isEqualTo(200);
        var finals = finalAnswers(fixture);
        var body = Map.of("expectedVersion", 2, "answerItems", finals);
        assertThat(new Browser().submit(id, "anonymous", body).status()).isEqualTo(401);
        assertThat(fixture.admin().submit(id, "admin", body).status()).isEqualTo(403);
        var other = new Browser();
        other.write("POST", "/auth/register", Map.of("username", "FreezeOther", "password", PASSWORD, "gradeId", GRADE));
        other.login("FreezeOther", PASSWORD);
        assertThat(other.submit(id, "other", body).status()).isEqualTo(404);
        assertThat(student.submit(id, null, body).status()).isEqualTo(400);
        assertThat(student.submit(id, "stale", Map.of("expectedVersion", 1, "answerItems", finals)).status()).isEqualTo(409);
        for (var invalid : java.util.List.of(
                java.util.List.of(finals.get(0), finals.get(0)),
                java.util.List.of(finals.get(0), Map.of("questionId", UUID.randomUUID(), "answer", Map.of("text", "外题"))),
                java.util.List.of(finals.get(0), answer(fixture, 1, Map.of("optionIds", java.util.List.of("X")))),
                java.util.List.of(finals.get(0), answer(fixture, 4, Map.of("text", "x".repeat(10001)))))) {
            assertThat(student.submit(id, "invalid", Map.of("expectedVersion", 2, "answerItems", invalid)).status()).isEqualTo(400);
        }
        assertThat(student.get(path).data().path("savedAnswers").get(0).path("answer").path("text").asText()).isEqualTo("旧草稿");
        jdbc.execute("CREATE FUNCTION fail_freeze_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'freeze failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_freeze_test BEFORE INSERT ON attempt_results FOR EACH ROW EXECUTE FUNCTION fail_freeze_test()");
        try {
            assertThat(student.submit(id, "final", body).status()).isEqualTo(503);
            var unchanged = student.get(path).data();
            assertThat(unchanged.path("version").asInt()).isEqualTo(2);
            assertThat(unchanged.path("status").asText()).isEqualTo("IN_PROGRESS");
            assertThat(unchanged.path("savedAnswers").get(0).path("answer").path("text").asText()).isEqualTo("旧草稿");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attempt_submit_keys", Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_freeze_test ON attempt_results");
            jdbc.execute("DROP FUNCTION fail_freeze_test()");
        }
        var frozen = student.submit(id, "final", body);
        assertThat(frozen.status()).isEqualTo(200);
        assertThat(frozen.data().path("status").asText()).isEqualTo("WAIT_SELF_GRADE");
        assertThat(frozen.data().path("freezeReason").asText()).isEqualTo("MANUAL");
        assertThat(frozen.data().path("version").asInt()).isEqualTo(3);
        assertThat(frozen.data().path("automaticScore").asText()).isEqualTo("0.30");
        assertThat(frozen.data().path("finalScore").isNull()).isTrue();
        assertThat(frozen.data().path("savedAnswers").size()).isEqualTo(5);
        assertThat(frozen.response().body()).doesNotContain("correctAnswer", "explanationHtml", "参考");
        assertThat(student.submit(id, "final", body).data().path("version").asInt()).isEqualTo(3);
        var changed = Map.of("expectedVersion", 2, "answerItems", java.util.List.of());
        assertThat(student.submit(id, "final", changed).status()).isEqualTo(409);
        assertThat(student.submit(id, "new-key", changed).data().path("savedAnswers")).isEqualTo(frozen.data().path("savedAnswers"));
        assertThat(student.write("PUT", path + "/answers", changed).status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempt_results", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mistakes", Integer.class)).isZero();
        String another = student.start(fixture.paper(), "another").data().path("id").asText();
        assertThat(student.submit(another, "final", body).status()).isEqualTo(409);
        assertThat(student.get("/me/attempts/" + another).data().path("status").asText()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void freezesPersistedAnswersOnReadAndLateSaveWithoutRollingBackTheFreeze() throws Exception {
        var fixture = freezingFixture(false);
        String path = "/me/attempts/" + fixture.id();
        var persisted = java.util.List.of(answer(fixture, 0, Map.of("optionId", "A")));
        fixture.student().write("PUT", path + "/answers", Map.of("expectedVersion", 1, "answerItems", persisted));
        clock.override = Instant.parse("2026-11-10T00:20:00Z");
        var frozen = fixture.student().get(path).data();
        assertThat(frozen.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(frozen.path("freezeReason").asText()).isEqualTo("DEADLINE");
        assertThat(frozen.path("finalScore").asText()).isEqualTo("0.10");
        assertThat(frozen.path("savedAnswers").size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mistakes", Integer.class)).isEqualTo(2);
        var late = fixture.student().submit(fixture.id(), "late", Map.of("expectedVersion", 2, "answerItems", finalAnswers(fixture)));
        assertThat(late.status()).isEqualTo(200);
        assertThat(late.data().path("savedAnswers")).isEqualTo(frozen.path("savedAnswers"));
        assertThat(late.data().path("frozenAt")).isEqualTo(frozen.path("frozenAt"));
        String another = fixture.student().start(fixture.paper(), "after-completed").data().path("id").asText();
        assertThat(another).isNotEqualTo(fixture.id());
        clock.override = Instant.parse("2026-11-10T00:40:00Z");
        // Renew authentication; idle sessions and deadline clocks are independent concerns.
        fixture.student().login("FreezeStudent", PASSWORD);
        assertThat(fixture.student().write("PUT", "/me/attempts/" + another + "/answers",
                Map.of("expectedVersion", 1, "answerItems", finalAnswers(fixture))).status()).isEqualTo(409);
        var afterLateSave = fixture.student().get("/me/attempts/" + another).data();
        assertThat(afterLateSave.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(afterLateSave.path("finalScore").asText()).isEqualTo("0.00");
        assertThat(afterLateSave.path("savedAnswers").size()).isZero();
        assertThat(afterLateSave.path("version").asInt()).isEqualTo(2);
        assertThat(lifecycle.freezeDue()).isZero();
        // Frozen state still prevents a write after the 24-hour idempotency record expires.
        clock.override = Instant.parse("2026-11-11T01:00:00Z");
        fixture.student().login("FreezeStudent", PASSWORD);
        assertThat(fixture.student().submit(fixture.id(), "late", Map.of("expectedVersion", 2,
                "answerItems", finalAnswers(fixture))).data().path("finalScore").asText()).isEqualTo("0.10");
    }

    @Test
    void serializesManualDeadlineCompetitionAndRecoversOverdueDrafts() throws Exception {
        var fixture = freezingFixture(false);
        var stored = java.util.List.of(answer(fixture, 0, Map.of("optionId", "B")));
        fixture.student().write("PUT", "/me/attempts/" + fixture.id() + "/answers",
                Map.of("expectedVersion", 1, "answerItems", stored));
        try (var blocking = dataSource.getConnection()) {
            blocking.setAutoCommit(false);
            try (var lock = blocking.prepareStatement("UPDATE attempts SET version = version WHERE id = ?")) {
                lock.setObject(1, UUID.fromString(fixture.id())); lock.executeUpdate();
            }
            clock.override = Instant.parse("2026-11-10T00:19:59.999Z");
            var submit = CompletableFuture.supplyAsync(() -> {
                try { return fixture.student().submit(fixture.id(), "race", Map.of("expectedVersion", 2,
                        "answerItems", finalAnswers(fixture))); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            awaitAttemptLock();
            clock.override = Instant.parse("2026-11-10T00:20:00Z");
            assertThat(lifecycle.freezeDue()).isZero(); // SKIP LOCKED does not block other candidates.
            blocking.commit();
            var frozen = submit.get(10, TimeUnit.SECONDS);
            assertThat(frozen.status()).isEqualTo(200);
            assertThat(frozen.data().path("freezeReason").asText()).isEqualTo("DEADLINE");
            assertThat(frozen.data().path("finalScore").asText()).isEqualTo("0.00");
            assertThat(frozen.data().path("savedAnswers").get(0).path("answer").path("optionId").asText()).isEqualTo("B");
            assertThat(frozen.data().path("version").asInt()).isEqualTo(3);
        }
        assertThat(lifecycle.freezeDue()).isZero();
        String backlog = fixture.student().start(fixture.paper(), "recovery").data().path("id").asText();
        fixture.student().write("PUT", "/me/attempts/" + backlog + "/answers",
                Map.of("expectedVersion", 1, "answerItems", finalAnswers(fixture)));
        clock.override = Instant.parse("2026-11-10T03:00:00Z");
        var gate = new java.util.concurrent.CountDownLatch(1);
        var workers = java.util.stream.IntStream.range(0, 2).mapToObj(index -> CompletableFuture.supplyAsync(() -> {
            try { gate.await(); return lifecycle.freezeDue(); }
            catch (InterruptedException error) { throw new RuntimeException(error); }
        })).toList();
        gate.countDown();
        assertThat(workers.get(0).join() + workers.get(1).join()).isEqualTo(1);
        fixture.student().login("FreezeStudent", PASSWORD);
        var recovered = fixture.student().get("/me/attempts/" + backlog).data();
        assertThat(recovered.path("deadlineAt").asText()).isEqualTo("2026-11-10T00:40:00Z");
        assertThat(recovered.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(recovered.path("finalScore").asText()).isEqualTo("0.30");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempt_results WHERE attempt_id = ?", Integer.class,
                UUID.fromString(backlog))).isEqualTo(3);
        // A later correct answer must not delete the earlier mistake entry.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mistakes", Integer.class)).isEqualTo(3);
    }

    private void awaitAttemptLock() throws InterruptedException {
        for (int retry = 0; retry < 100; retry++) {
            if (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                    + "AND query LIKE '%FROM attempts WHERE id =%'", Integer.class) > 0) return;
            Thread.sleep(25);
        }
        throw new AssertionError("HTTP request did not reach the locked attempt");
    }

    @Test
    void gradesUnorderedPartialExtraAndMissingSelectionsUsingSnapshotIntegers() throws Exception {
        var fixture=freezingFixture(false);
        String subject=fixture.admin().get("/admin/papers/"+fixture.paper()).data().path("paper").path("subjectId").asText();
        var options=java.util.List.of(Map.of("id","A","text","甲"),Map.of("id","B","text","乙"),Map.of("id","C","text","丙"));
        var question=Map.of("subjectId",subject,"type","MULTIPLE","difficulty",1,"score","0.20","stemHtml","集合比较", "options",options,"answer",Map.of("optionIds",java.util.List.of("A","B")));
        assertThat(fixture.admin().write("PATCH","/admin/questions/"+fixture.questions().get(1),Map.of("expectedVersion",1,"question",question)).status()).isEqualTo(200);
        var created=fixture.admin().write("POST","/admin/papers",Map.of("subjectId",subject,"type","FIXED","draft",Map.of("name","客观边界卷","durationMinutes",20,"groups",java.util.List.of(Map.of("name","客观题","questionIds",fixture.questions().subList(0,2))))));
        String paper=created.data().path("paper").path("id").asText();fixture.admin().write("POST","/admin/papers/"+paper+"/publish",Map.of("expectedVersion",1));
        var selections=java.util.List.of(java.util.List.of("B","A"),java.util.List.of("A"),java.util.List.of("A","C"),java.util.List.<String>of());
        for(int index=0;index<selections.size();index++) {
            String id=fixture.student().start(paper,"selection-"+index).data().path("id").asText();
            var answers=new java.util.ArrayList<Map<String,Object>>();answers.add(answer(fixture,0,Map.of("optionId","A")));
            if(!selections.get(index).isEmpty())answers.add(answer(fixture,1,Map.of("optionIds",selections.get(index))));
            var result=fixture.student().submit(id,"final-selection-"+index,Map.of("expectedVersion",1,"answerItems",answers));
            assertThat(result.status()).isEqualTo(200);assertThat(result.data().path("finalScore").asText()).isEqualTo(index==0?"0.30":"0.10");
        }
    }

    @Test
    void reportsHongKongMonthBoundariesAndDeadlineAlertsWithoutExposingAnswers() throws Exception {
        var fixture=freezingFixture(false);
        clock.override=Instant.parse("2026-11-10T00:20:31Z");
        fixture.admin().login("MainAdmin",PASSWORD);
        var lag=fixture.admin().get("/admin/observability");assertThat(lag.status()).isEqualTo(200);
        assertThat(lag.data().path("databaseUp").asBoolean()).isTrue();assertThat(lag.data().path("alerts").toString()).contains("DEADLINE_BACKLOG");
        assertThat(lag.response().body()).doesNotContain("correctAnswer",PASSWORD);
        fixture.student().get("/me/attempts/"+fixture.id());
        assertThat(fixture.admin().get("/admin/observability").data().path("overdueCount").asInt()).isZero();
        clock.override=Instant.parse("2026-10-31T16:00:00Z");
        UUID owner=UUID.fromString(fixture.student().get("/me").data().path("id").asText());
        jdbc.update("DELETE FROM user_daily_activity");
        jdbc.update("INSERT INTO user_daily_activity(user_id,day) VALUES (?,DATE '2026-11-01') ON CONFLICT DO NOTHING",owner);
        jdbc.update("INSERT INTO user_daily_activity(user_id,day) VALUES (?,DATE '2026-11-01') ON CONFLICT DO NOTHING",owner);
        jdbc.update("UPDATE questions SET created_at=TIMESTAMPTZ '2026-10-31 15:59:59Z'");
        jdbc.update("UPDATE questions SET created_at=TIMESTAMPTZ '2026-10-31 16:00:00Z' WHERE id=?",UUID.fromString(fixture.questions().get(0)));
        var stats=fixture.admin().get("/admin/statistics").data();
        assertThat(stats.path("timeZone").asText()).isEqualTo("Asia/Hong_Kong");
        assertThat(stats.path("activeUsers").size()).isEqualTo(7);assertThat(stats.path("activeUsers").get(6).path("count").asInt()).isEqualTo(1);
        assertThat(stats.path("activeUsers").get(0).path("count").asInt()).isZero();
        assertThat(stats.path("newQuestions").size()).isEqualTo(1);assertThat(stats.path("newQuestions").get(0).path("count").asInt()).isEqualTo(1);
        jdbc.update("UPDATE questions SET archived=true WHERE id=?",UUID.fromString(fixture.questions().get(0)));
        var archived=fixture.admin().get("/admin/statistics").data();assertThat(archived.path("questionCount").asInt()).isEqualTo(2);
        assertThat(archived.path("newQuestions").get(0).path("count").asInt()).isEqualTo(1);
    }

    @Test
    void alertsOnlyAfterFiveMinutesOfSustainedServiceErrors() {
        clock.override=Instant.parse("2026-11-10T00:00:00Z");
        var isolated=new com.onlineexam.platform.service.ObservabilityService(org.springframework.jdbc.core.simple.JdbcClient.create(dataSource),clock,dataSource);
        isolated.record(20,503);assertThat(isolated.metrics().alerts()).doesNotContain("ERROR_RATE_HIGH");
        clock.override=Instant.parse("2026-11-10T00:05:00Z");isolated.record(30,503);
        assertThat(isolated.metrics().alerts()).contains("ERROR_RATE_HIGH");
        clock.override=Instant.parse("2026-11-10T00:10:01Z");
        assertThat(isolated.metrics().alerts()).doesNotContain("ERROR_RATE_HIGH");
    }

    @Test
    void authorizesSnapshotImagesOnlyForTheOwnerAndHidesExplanationsUntilFrozen() throws Exception {
        var fixture=freezingFixture(false);String subject=fixture.admin().get("/admin/papers/"+fixture.paper()).data().path("paper").path("subjectId").asText();
        var image=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"PNG",image);
        String attachment=fixture.admin().upload("answer.png",image.toByteArray()).data().path("id").asText();
        var question=fixture.admin().write("POST","/admin/questions",Map.of("subjectId",subject,"type","SHORT","difficulty",1,"score","1.00","stemHtml","私有解析题","options",java.util.List.of(),"answer",Map.of("text","参考"),"explanationHtml","<img data-attachment=\""+attachment+"\" alt=\"解析图片\">"));
        String q=question.data().path("id").asText();String paper=createPublishedPaper(fixture.admin(),subject,q,"FIXED","解析图片卷");
        String id=fixture.student().start(paper,"image-start").data().path("id").asText();String path="/me/attempts/"+id+"/questions/"+q+"/attachments/"+attachment;
        assertThat(fixture.student().http.send(fixture.student().prepare("GET",path,null,false),HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(404);
        fixture.student().submit(id,"image-final",Map.of("expectedVersion",1,"answerItems",java.util.List.of()));
        var visible=fixture.student().http.send(fixture.student().prepare("GET",path,null,false),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(visible.statusCode()).isEqualTo(200);assertThat(visible.body()).isEqualTo(image.toByteArray());
        var other=new Browser();other.write("POST","/auth/register",Map.of("username","ImageOther","password",PASSWORD,"gradeId",GRADE));other.login("ImageOther",PASSWORD);
        assertThat(other.http.send(other.prepare("GET",path,null,false),HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(404);
        assertThat(new Browser().http.send(new Browser().prepare("GET",path,null,false),HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(401);
    }

    @Test
    void confirmsSelfGradesWithAtomicMistakesAndRejectsIllegalScores(CapturedOutput output) throws Exception {
        var fixture=freezingFixture(true);
        var submitted=fixture.student().submit(fixture.id(),"grade-submit",Map.of("expectedVersion",1,"answerItems",finalAnswers(fixture)));
        assertThat(submitted.status()).isEqualTo(200);
        String path="/me/attempts/"+fixture.id()+"/self-grade";
        String reviewPath="/me/attempts/"+fixture.id()+"/review";
        var valid=java.util.List.of(Map.of("questionId",fixture.questions().get(3),"score","0.50"),
                Map.of("questionId",fixture.questions().get(4),"score","1.00"));
        assertThat(fixture.student().get(reviewPath).data().path("items").get(3).path("referenceAnswer").has("gaps")).isTrue();
        for(var invalid:java.util.List.of(java.util.List.of(valid.get(0)),
                java.util.List.of(valid.get(0),Map.of("questionId",fixture.questions().get(4),"score","1.01")),
                java.util.List.of(valid.get(0),Map.of("questionId",fixture.questions().get(4),"score","0.001")),
                java.util.List.of(valid.get(0),Map.of("questionId",fixture.questions().get(4),"score","-1")),
                java.util.List.of(valid.get(0),valid.get(0)),java.util.List.of(valid.get(0),Map.of("questionId",fixture.questions().get(0),"score","0.10")))) {
            assertThat(fixture.student().keyed(path,"bad",Map.of("expectedVersion",2,"scores",invalid)).status()).isEqualTo(400);
        }
        assertThat(fixture.student().get(reviewPath).data().path("attempt").path("version").asInt()).isEqualTo(2);
        jdbc.execute("CREATE FUNCTION fail_grade_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='COMPLETED' THEN RAISE EXCEPTION 'grading failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_grade_test BEFORE UPDATE ON attempts FOR EACH ROW EXECUTE FUNCTION fail_grade_test()");
        var body=Map.of("expectedVersion",2,"scores",valid);
        try {
            assertThat(fixture.student().keyed(path,"confirm",body).status()).isEqualTo(503);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM gradings",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM mistakes",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attempt_results WHERE score_minor IS NULL",Integer.class)).isEqualTo(2);
        } finally { jdbc.execute("DROP TRIGGER fail_grade_test ON attempts");jdbc.execute("DROP FUNCTION fail_grade_test()"); }
        // Fail after scores and COMPLETED have been written: task projection must roll back too.
        jdbc.execute("CREATE FUNCTION fail_mistake_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'late grading failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_mistake_test BEFORE INSERT ON mistakes FOR EACH ROW EXECUTE FUNCTION fail_mistake_test()");
        try {
            assertThat(fixture.student().keyed(path,"confirm",body).status()).isEqualTo(503);
            assertThat(fixture.student().get(reviewPath).data().path("attempt").path("status").asText()).isEqualTo("WAIT_SELF_GRADE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM gradings",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attempt_results WHERE score_minor IS NULL",Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM operation_keys WHERE operation='SELF_GRADE'",Integer.class)).isZero();
        } finally { jdbc.execute("DROP TRIGGER fail_mistake_test ON mistakes");jdbc.execute("DROP FUNCTION fail_mistake_test()"); }
        var second=new Browser();second.login("FreezeStudent",PASSWORD);
        var gate=new java.util.concurrent.CountDownLatch(1);
        var one=CompletableFuture.supplyAsync(()->{try {gate.await();return fixture.student().keyed(path,"confirm",body);}catch(Exception e){throw new RuntimeException(e);}});
        var two=CompletableFuture.supplyAsync(()->{try {gate.await();return second.keyed(path,"confirm",body);}catch(Exception e){throw new RuntimeException(e);}});
        gate.countDown();
        assertThat(one.join().status()).isEqualTo(200);assertThat(two.join().status()).isEqualTo(200);
        var complete=fixture.student().get(reviewPath).data();
        assertThat(complete.path("attempt").path("finalScore").asText()).isEqualTo("1.80");
        assertThat(complete.path("gradingSource").asText()).isEqualTo("SELF");
        assertThat(fixture.student().keyed(path,"repeat-new-key",body).status()).isEqualTo(200);
        assertThat(fixture.student().keyed(path,"modify-final",Map.of("expectedVersion",3,"scores",java.util.List.of(
                Map.of("questionId",fixture.questions().get(3),"score","0.40"),valid.get(1)))).status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gradings",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mistakes",Integer.class)).isEqualTo(2);
        assertThat(fixture.student().keyed(path,"confirm",Map.of("expectedVersion",2,"scores",java.util.List.of())).status()).isEqualTo(409);
        assertThat(fixture.student().get("/me/attempts?ownerId="+UUID.randomUUID()).status()).isEqualTo(400);
        String owner=fixture.student().get("/me").data().path("id").asText();
        assertThat(output.getOut()).doesNotContain("failed to append","has already been written");
        var failureLogs=output.getOut().lines().filter(line->line.startsWith("{")&&line.contains("\"message\":\"Database request failed\"")).toList();
        assertThat(failureLogs.size()).isGreaterThanOrEqualTo(2);
        for(String line:failureLogs) {
            var event=json.readTree(line);
            assertThat(event.path("actorId").asText()).isEqualTo(owner);
            assertThat(event.path("requestId").asText()).isNotBlank();
            assertThat(event.path("module").asText()).isEqualTo("http");
            assertThat(event.path("action").asText()).isEqualTo("POST");
            assertThat(event.path("result").asText()).isEqualTo("P0001");
            assertThat(line).doesNotContain(PASSWORD,"最终答案","SELECT ","INSERT ");
        }
        var adminReview=fixture.admin().get("/admin/attempts/"+fixture.id()).data();
        assertThat(adminReview.path("attempt").path("finalScore").asText()).isEqualTo("1.80");
        assertThat(fixture.admin().get("/admin/attempts?ownerId="+owner).data().path("total").asInt()).isEqualTo(1);
        assertThat(fixture.student().get("/me/mistakes").data().path("total").asInt()).isEqualTo(2);
        var other=new Browser();other.write("POST","/auth/register",Map.of("username","ReviewOther","password",PASSWORD,"gradeId",GRADE));other.login("ReviewOther",PASSWORD);
        assertThat(other.get(reviewPath).status()).isEqualTo(404);
        assertThat(other.keyed(path,"other",body).status()).isEqualTo(404);
        assertThat(other.get("/me/mistakes/"+fixture.questions().get(2)).status()).isEqualTo(404);
    }

    @Test
    void projectsTaskCompletionOnlyAfterEverySubjectiveGradeAndKeepsEmptyAnswersZero() throws Exception {
        var fixture=freezingFixture(true);
        String subject=fixture.admin().get("/admin/papers/"+fixture.paper()).data().path("paper").path("subjectId").asText();
        String first=createPublishedPaper(fixture.admin(),subject,fixture.questions().get(4),"TASK","主观甲");
        String second=createPublishedPaper(fixture.admin(),subject,fixture.questions().get(4),"TASK","主观乙");
        var task=fixture.admin().write("POST","/admin/tasks",Map.of("gradeId",GRADE,"name","批改任务","paperIds",java.util.List.of(first,second))).data();
        String taskId=task.path("id").asText();
        fixture.admin().write("POST","/admin/tasks/"+taskId+"/publish",Map.of("expectedVersion",1));
        String firstAttempt=fixture.student().startTask(taskId,first,"task-a").data().path("id").asText();
        String secondAttempt=fixture.student().startTask(taskId,second,"task-b").data().path("id").asText();
        fixture.student().submit(firstAttempt,"submit-a",Map.of("expectedVersion",1,"answerItems",java.util.List.of(answer(fixture,4,Map.of("text","作答")))));
        fixture.student().submit(secondAttempt,"submit-b",Map.of("expectedVersion",1,"answerItems",java.util.List.of()));
        String taskPath="/me/tasks/"+taskId;
        var pending=fixture.student().get(taskPath).data();
        assertThat(pending.path("completed").asBoolean()).isFalse();
        assertThat(pending.path("papers").get(0).path("status").asText()).isEqualTo("WAIT_SELF_GRADE");
        assertThat(pending.path("papers").get(0).path("attemptId").asText()).isEqualTo(firstAttempt);
        fixture.student().keyed("/me/attempts/"+firstAttempt+"/self-grade","grade-a",Map.of("expectedVersion",2,"scores",java.util.List.of(Map.of("questionId",fixture.questions().get(4),"score","0.50"))));
        assertThat(fixture.student().get(taskPath).data().path("completedCount").asInt()).isEqualTo(1);
        var bad=Map.of("expectedVersion",2,"scores",java.util.List.of(Map.of("questionId",fixture.questions().get(4),"score","1.00")));
        assertThat(fixture.student().keyed("/me/attempts/"+secondAttempt+"/self-grade","grade-b",bad).status()).isEqualTo(400);
        assertThat(fixture.student().keyed("/me/attempts/"+secondAttempt+"/self-grade","grade-b",Map.of("expectedVersion",2,"scores",java.util.List.of(Map.of("questionId",fixture.questions().get(4),"score","0.00")))).status()).isEqualTo(200);
        var complete=fixture.student().get(taskPath).data();
        assertThat(complete.path("completed").asBoolean()).isTrue();assertThat(complete.path("completedCount").asInt()).isEqualTo(2);
        assertThat(fixture.student().startTask(taskId,first,"new-start").data().path("id").asText()).isEqualTo(firstAttempt);
    }

    @Test
    void sendsMessagesAtomicallyDeduplicatesRecipientsAndCountsFirstReadOnce() throws Exception {
        var fixture=freezingFixture(false);
        String owner=fixture.student().get("/me").data().path("id").asText();
        var other=new Browser();String outsider=other.write("POST","/auth/register",Map.of("username","MessageOther","password",PASSWORD,"gradeId",GRADE)).data().path("id").asText();
        other.login("MessageOther",PASSWORD);
        var invalid=Map.of("title","消息","body","仅测试文本","recipientIds",java.util.List.of(owner,UUID.randomUUID().toString()));
        assertThat(fixture.admin().keyed("/admin/messages","send",invalid).status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages",Integer.class)).isZero();
        String invalidUser=fixture.admin().write("POST","/admin/users",Map.of("username","InvalidRecipient","password",PASSWORD,"role","STUDENT","gradeId",GRADE)).data().path("id").asText();
        for(String status:java.util.List.of("DISABLED","DELETED")) {
            jdbc.update("UPDATE users SET status=? WHERE id=?",status,UUID.fromString(invalidUser));
            assertThat(fixture.admin().keyed("/admin/messages","invalid-"+status,Map.of("title","无效收件人","body","整体拒绝","recipientIds",java.util.List.of(owner,invalidUser))).status()).isEqualTo(400);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM messages",Integer.class)).isZero();
        }
        var body=Map.of("title","任务提醒","body","批改后查看记录","recipientIds",java.util.List.of(owner,owner));
        var sent=fixture.admin().keyed("/admin/messages","send",body);assertThat(sent.status()).isEqualTo(200);
        String id=sent.data().path("message").path("id").asText();
        assertThat(sent.data().path("message").path("recipientCount").asInt()).isEqualTo(1);
        assertThat(fixture.admin().keyed("/admin/messages","send",body).data().path("message").path("id").asText()).isEqualTo(id);
        assertThat(fixture.admin().keyed("/admin/messages","send",invalid).status()).isEqualTo(409);
        assertThat(other.get("/me/messages/"+id).status()).isEqualTo(404);
        assertThat(other.write("PUT","/me/messages/"+id+"/read",null).status()).isEqualTo(404);
        assertThat(fixture.student().get("/me/messages/"+id).data().has("recipientCount")).isFalse();
        var one=fixture.student().write("PUT","/me/messages/"+id+"/read",null).data().path("readAt");
        assertThat(fixture.student().write("PUT","/me/messages/"+id+"/read",null).data().path("readAt")).isEqualTo(one);
        assertThat(fixture.admin().get("/admin/messages/"+id).data().path("message").path("readCount").asInt()).isEqualTo(1);
        assertThat(fixture.student().keyed("/admin/messages","illegal",body).status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages",Integer.class)).isEqualTo(1);
        assertThat(fixture.student().get("/me/messages").data().path("total").asInt()).isEqualTo(1);
        assertThat(other.get("/me/messages").data().path("total").asInt()).isZero();
    }

    @Test
    void protectsProfilesAndAvatarsAndCorrelatesCredentialFreeAudit() throws Exception {
        var fixture=freezingFixture(false);
        var profile=fixture.student().get("/me/profile").data();
        var body=Map.of("expectedVersion",profile.path("version").asInt(),"name","学生姓名","phone","+852 12345678","birthday","2000-01-01");
        assertThat(fixture.student().write("PATCH","/me/profile",body).status()).isEqualTo(200);
        assertThat(fixture.student().get("/me/profile").data().path("name").asText()).isEqualTo("学生姓名");
        assertThat(fixture.student().write("PATCH","/me/profile",Map.of("expectedVersion",2,"name","非法","phone","","role","ADMIN")).status()).isEqualTo(400);
        var image=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"PNG",image);
        assertThat(fixture.student().uploadPath("/me/avatar","avatar.png",image.toByteArray()).status()).isEqualTo(200);
        var imageResponse=fixture.student().http.send(fixture.student().prepare("GET","/me/avatar/image",null,false),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(imageResponse.statusCode()).isEqualTo(200);assertThat(imageResponse.body()).isEqualTo(image.toByteArray());
        assertThat(fixture.student().uploadPath("/me/avatar","bad.png",new byte[]{1,2,3}).status()).isEqualTo(400);
        assertThat(fixture.student().get("/me/profile").data().path("avatarId").isNull()).isFalse();
        var submitted=fixture.student().submit(fixture.id(),"audit-submit",Map.of("expectedVersion",1,"answerItems",finalAnswers(fixture)));
        String requestId=json.readTree(submitted.response().body()).path("requestId").asText();
        assertThat(submitted.response().headers().firstValue("X-Request-Id").orElseThrow()).isEqualTo(requestId);
        var events=fixture.admin().get("/admin/audit-events?requestId="+requestId);
        assertThat(events.data().path("items").toString()).contains("Attempt.submit",fixture.id());
        assertThat(events.response().body()).doesNotContain(PASSWORD,"最终答案","correct_answer","passwordHash","token");
        assertThat(fixture.student().get("/admin/audit-events").status()).isEqualTo(403);
        assertThat(fixture.student().get("/me/activities?actorId="+UUID.randomUUID()).status()).isEqualTo(400);
        assertThat(fixture.student().get("/me/activities").data().path("total").asInt()).isGreaterThan(0);
    }

    @Test
    void concurrentManualSubmissionsReturnOneResultWithoutDuplicatingScores() throws Exception {
        var fixture = freezingFixture(false);
        var second = new Browser(); second.login("FreezeStudent", PASSWORD);
        var body = Map.of("expectedVersion", 1, "answerItems", finalAnswers(fixture));
        var gate = new java.util.concurrent.CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> {
            try { gate.await(); return fixture.student().submit(fixture.id(), "same-final", body); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        var retry = CompletableFuture.supplyAsync(() -> {
            try { gate.await(); return second.submit(fixture.id(), "same-final", body); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        gate.countDown();
        var one = first.get(10, TimeUnit.SECONDS);
        var two = retry.get(10, TimeUnit.SECONDS);
        assertThat(one.status()).isEqualTo(200);
        assertThat(two.status()).isEqualTo(200);
        assertThat(one.data().path("frozenAt")).isEqualTo(two.data().path("frozenAt"));
        assertThat(one.data().path("version").asInt()).isEqualTo(2);
        assertThat(two.data().path("finalScore").asText()).isEqualTo("0.30");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempt_results", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempt_submit_keys", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mistakes", Integer.class)).isEqualTo(1);
    }

    @Test
    void deadlineWorkerSkipsBusyOwnerAndUsesTheSameLockOrderAsHttp() throws Exception {
        var fixture = freezingFixture(false);
        clock.override = Instant.parse("2026-11-10T00:20:00Z");
        try (var blocking = dataSource.getConnection()) {
            blocking.setAutoCommit(false);
            try (var lock = blocking.prepareStatement("SELECT id FROM users WHERE username_normalized = 'freezestudent' FOR UPDATE")) {
                lock.executeQuery().close();
            }
            var skipped = CompletableFuture.supplyAsync(() -> lifecycle.freezeDue());
            assertThat(skipped.get(3, TimeUnit.SECONDS)).isZero();
            blocking.commit();
        }
        assertThat(lifecycle.freezeDue()).isEqualTo(1);
        assertThat(fixture.student().get("/me/attempts/" + fixture.id()).data().path("status").asText()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mistakes", Integer.class)).isEqualTo(3);
    }

    private record FreezeFixture(Browser admin, Browser student, String paper, String id, java.util.List<String> questions) {}

    private FreezeFixture freezingFixture(boolean mixed) throws Exception {
        clock.override = Instant.parse("2026-11-10T00:00:00Z");
        var admin = new Browser(); admin.login("MainAdmin", PASSWORD);
        var student = new Browser();
        student.write("POST", "/auth/register", Map.of("username", "FreezeStudent", "password", PASSWORD, "gradeId", GRADE));
        student.login("FreezeStudent", PASSWORD);
        String subject = admin.write("POST", "/admin/subjects", Map.of("gradeId", GRADE, "name", "冻结学科")).data().path("id").asText();
        var options = java.util.List.of(Map.of("id", "A", "text", "甲"), Map.of("id", "B", "text", "乙"));
        var variants = java.util.List.of(
                Map.of("type", "SINGLE", "score", "0.10", "options", options, "answer", Map.of("optionIds", java.util.List.of("A"))),
                Map.of("type", "MULTIPLE", "score", "0.20", "options", options, "answer", Map.of("optionIds", java.util.List.of("A", "B"))),
                Map.of("type", "TRUE_FALSE", "score", "0.30", "options", java.util.List.of(), "answer", Map.of("optionIds", java.util.List.of("true"))),
                Map.of("type", "GAP", "score", "1.00", "options", java.util.List.of(), "stemHtml", "<span data-gap=\"1\">___</span>",
                        "answer", Map.of("gaps", java.util.List.of("参考"))),
                Map.of("type", "SHORT", "score", "1.00", "options", java.util.List.of(), "answer", Map.of("text", "参考")));
        var questions = new java.util.ArrayList<String>();
        for (var variant : variants.subList(0, mixed ? 5 : 3)) {
            var question = new java.util.HashMap<String, Object>(Map.of("subjectId", subject, "difficulty", 3, "stemHtml", "冻结测试题"));
            question.putAll(variant);
            var result = admin.write("POST", "/admin/questions", question);
            assertThat(result.status()).isEqualTo(201);
            questions.add(result.data().path("id").asText());
        }
        var created = admin.write("POST", "/admin/papers", Map.of("subjectId", subject, "type", "FIXED", "draft",
                Map.of("name", "冻结测试卷", "durationMinutes", 20, "groups", java.util.List.of(Map.of("name", "题组", "questionIds", questions)))));
        String paper = created.data().path("paper").path("id").asText();
        assertThat(admin.write("POST", "/admin/papers/" + paper + "/publish", Map.of("expectedVersion", 1)).status()).isEqualTo(200);
        String id = student.start(paper, "freeze-start").data().path("id").asText();
        return new FreezeFixture(admin, student, paper, id, questions);
    }

    private Map<String, Object> answer(FreezeFixture fixture, int index, Object answer) {
        return Map.of("questionId", fixture.questions().get(index), "answer", answer);
    }

    private java.util.List<Map<String, Object>> finalAnswers(FreezeFixture fixture) {
        var result = new java.util.ArrayList<Map<String, Object>>();
        result.add(answer(fixture, 0, Map.of("optionId", "A")));
        result.add(answer(fixture, 1, Map.of("optionIds", java.util.List.of("B", "A"))));
        result.add(answer(fixture, 2, Map.of("optionId", "false")));
        if (fixture.questions().size() > 3) {
            result.add(answer(fixture, 3, Map.of("texts", java.util.List.of("填写"))));
            result.add(answer(fixture, 4, Map.of("text", "最终答案")));
        }
        return result;
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

    @Test
    void acceptsOnlyWebSessionsAndRevokesHistoricalCredentialsWithoutReopeningLegacyRoutes() throws Exception {
        var fixture=freezingFixture(false);
        String owner=fixture.student().get("/me").data().path("id").asText();
        UUID binding=UUID.randomUUID();String legacyToken="L".repeat(43);
        jdbc.update("INSERT INTO wechat_bindings(id,user_id,app_id,open_id) VALUES (?,?,?,?)",binding,UUID.fromString(owner),"retired-fixture","retired-subject");
        jdbc.update("INSERT INTO wechat_tokens(binding_id,token_hash,expires_at) VALUES (?,?,?)",binding,IdentityService.digest(legacyToken),java.time.OffsetDateTime.now(clock).plusHours(1));
        var tokenRequest=HttpRequest.newBuilder(fixture.student().prepare("GET","/me",null,false),(name,value)->true)
                .header("Authorization","Bearer "+legacyToken).build();
        assertThat(fixture.student().http.send(tokenRequest,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(fixture.student().get("/me").status()).isEqualTo(200);
        for(String action:java.util.List.of("bind","register","login"))
            assertThat(fixture.student().write("POST","/auth/wechat/"+action,Map.of()).status()).isEqualTo(403);
        assertThat(fixture.student().write("DELETE","/auth/wechat/binding",null).status()).isEqualTo(403);
        assertThat(fixture.student().write("POST","/me/password",Map.of("currentPassword",PASSWORD,"newPassword","Replacement-test-secret-9217")).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wechat_tokens",Integer.class)).isZero();
        int version=fixture.admin().get(USERS+"/"+owner).data().path("version").asInt();
        assertThat(fixture.admin().write("DELETE",USERS+"/"+owner,Map.of("expectedVersion",version)).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wechat_bindings",Integer.class)).isZero();
        for(String type:java.util.List.of("Application/JSON; charset=UTF-8","application/problem+json")) {
            var oversized=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/version"))
                    .header("Content-Type",type).method("GET",HttpRequest.BodyPublishers.ofString(" ".repeat(2*1024*1024+1))).build();
            var response=HttpClient.newHttpClient().send(oversized,HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        }
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

        Result submit(String id, String key, Object body) throws Exception {
            return keyed("/me/attempts/" + id + "/submit",key,body);
        }
        Result keyed(String path,String key,Object body) throws Exception {
            var request = HttpRequest.newBuilder(prepare("POST", path, body, true), (name, value) -> true);
            if (key != null) request.header("Idempotency-Key", key);
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Result(response, json.readTree(response.body()).path("data"));
        }

        Result get(String path) throws Exception { return request("GET", path, null, false); }

        Result write(String method, String path, Object body) throws Exception {
            return request(method, path, body, true);
        }

        Result start(String paper, String key) throws Exception {
            return startPath("/me/papers/" + paper + "/attempts", key);
        }

        Result startTask(String task, String paper, String key) throws Exception {
            return startPath("/me/tasks/" + task + "/papers/" + paper + "/attempts", key);
        }

        Result startPath(String path, String key) throws Exception {
            var csrf = get("/auth/csrf");
            var csrfCookie = csrf.response().headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/api/v1" + path))
                    .header("Cookie", session + "; " + csrfCookie)
                    .header(csrf.data().path("headerName").asText(), csrf.data().path("token").asText());
            if (key != null) request.header("Idempotency-Key", key);
            var response = http.send(request.POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Result(response, json.readTree(response.body()).path("data"));
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
            return uploadPath("/attachments",name,bytes);
        }
        Result uploadPath(String path,String name,byte[] bytes) throws Exception {
            var csrf = get("/auth/csrf");
            var cookie = csrf.response().headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            String boundary = "upload-boundary";
            var body = HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\n"
                            + "Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
                            + "Content-Type: image/png\r\n\r\n"),
                    HttpRequest.BodyPublishers.ofByteArray(bytes),
                    HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1"+path))
                    .header("Cookie", session + "; " + cookie)
                    .header(csrf.data().path("headerName").asText(), csrf.data().path("token").asText())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(body).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Result(response, json.readTree(response.body()).path("data"));
        }
    }
}
