package com.onlineexam.platform.service;

import com.onlineexam.platform.domain.ApiException;
import com.onlineexam.platform.domain.Identity.Account;
import com.onlineexam.platform.domain.Identity.AccountView;
import com.onlineexam.platform.domain.Identity.Role;
import com.onlineexam.platform.domain.Identity.Session;
import com.onlineexam.platform.domain.Identity.Status;
import com.onlineexam.platform.repository.AccountRepository;
import com.onlineexam.platform.repository.SessionRepository;
import com.onlineexam.platform.repository.WeChatRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
    private final AccountRepository accounts;
    private final SessionRepository sessions;
    private final WeChatRepository wechat;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final String dummyHash = passwords.encode("identity-missing-user");

    public record Page(List<AccountView> items, long total, int page, int pageSize) {}

    public IdentityService(AccountRepository accounts, SessionRepository sessions, WeChatRepository wechat, Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.wechat = wechat;
        this.clock = clock;
    }

    @Transactional
    public AccountView register(String name, String password, UUID grade) {
        return create(name, password, Role.STUDENT, grade).view();
    }

    @Transactional
    public void bootstrap(String name, String password) {
        accounts.lockAdministration();
        if (accounts.enabledAdmins() == 0) create(name, password, Role.ADMIN, null);
    }

    @Transactional(noRollbackFor = LoginRejected.class)
    public Session login(String name, String password, String ip, String previousToken) {
        Account account = verifyCredentials(name, password, ip);
        revoke(previousToken);
        String token = newToken();
        sessions.create(digest(token), account.id(), OffsetDateTime.now(clock));
        return new Session(token, account.view());
    }

    @Transactional(noRollbackFor = LoginRejected.class)
    public AccountView authenticateStudent(String name, String password, String ip) {
        Account account = verifyCredentials(name, password, ip);
        if (account.role() != Role.STUDENT) throw ApiException.forbidden();
        return account.view();
    }

    private Account verifyCredentials(String name, String password, String ip) {
        String normalized = canonicalName(name).toLowerCase(Locale.ROOT);
        validatePassword(password);
        OffsetDateTime now = OffsetDateTime.now(clock);
        String accountKey = "account:" + digest(normalized);
        String ipKey = "ip:" + digest(ip);
        int accountFailures = sessions.lockFailures(accountKey, now);
        int ipFailures = sessions.lockFailures(ipKey, now);
        if (accountFailures >= 5 || ipFailures >= 5) {
            throw new LoginRejected(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "登录尝试过多，请稍后重试。");
        }
        Account account = accounts.byUsernameForUpdate(normalized).orElse(null);
        String hash = account == null || account.passwordHash() == null ? dummyHash : account.passwordHash();
        if (!passwords.matches(password, hash) || account == null || account.status() != Status.ENABLED) {
            sessions.addFailure(accountKey);
            sessions.addFailure(ipKey);
            throw new LoginRejected(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "用户名或密码错误。");
        }
        return account;
    }

    String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public void revoke(String token) {
        if (validToken(token)) sessions.revoke(digest(token));
    }

    public AccountView current(UUID id) {
        var account = accounts.byId(id).orElseThrow(ApiException::unauthenticated);
        if (account.status() != Status.ENABLED) throw ApiException.unauthenticated();
        return account.view();
    }

    @Transactional
    public void changePassword(UUID id, String current, String replacement) {
        validatePassword(current);
        validatePassword(replacement);
        Account account = accounts.lockAccount(id).orElseThrow(ApiException::unauthenticated);
        if (account.status() != Status.ENABLED || !passwords.matches(current, account.passwordHash())) {
            throw ApiException.unauthenticated();
        }
        accounts.updatePassword(id, passwords.encode(replacement));
        sessions.revokeUser(id);
        wechat.revokeUser(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(UUID requester, Role role, UUID grade, int page, int size, String sort) {
        requireAdmin(requester);
        if (page < 1 || size < 1 || size > 100 || !List.of("createdAt", "username").contains(sort)) {
            throw ApiException.validation("页码、页大小或排序字段无效。");
        }
        return new Page(accounts.list(role, grade, size, (long) (page - 1) * size, sort)
                .stream().map(Account::view).toList(), accounts.count(role, grade), page, size);
    }

    public AccountView detail(UUID requester, UUID id) {
        requireAdmin(requester);
        return target(id).view();
    }

    @Transactional
    public AccountView createManaged(UUID requester, String name, String password, Role role, UUID grade) {
        accounts.lockAdministration();
        requireAdmin(requester);
        return create(name, password, role, grade).view();
    }

    @Transactional
    public AccountView edit(UUID requester, UUID id, int expectedVersion, String name, Role role, UUID grade) {
        accounts.lockAdministration();
        requireAdmin(requester);
        Account before = versioned(id, expectedVersion);
        String username = canonicalName(name);
        validateRoleGrade(role, grade);
        protectLastAdmin(before, role, before.status());
        Account updated = update(before, username, role, before.status(), grade);
        if (before.role() != role) {
            sessions.revokeUser(id);
            wechat.removeBinding(id);
        }
        return updated.view();
    }

    @Transactional
    public AccountView changeStatus(UUID requester, UUID id, int expectedVersion, Status desired) {
        accounts.lockAdministration();
        requireAdmin(requester);
        Account before = versioned(id, expectedVersion);
        protectLastAdmin(before, before.role(), desired);
        if (before.status() == desired) return before.view();
        String username = desired == Status.DELETED ? "deleted_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24)
                : before.username();
        Account updated = update(before, username, before.role(), desired, desired == Status.DELETED ? null : before.gradeId());
        if (desired != Status.ENABLED) {
            sessions.revokeUser(id);
            if (desired == Status.DELETED) wechat.removeBinding(id);
            else wechat.revokeUser(id);
        }
        return updated.view();
    }

    private Account create(String name, String password, Role role, UUID grade) {
        String username = canonicalName(name);
        validatePassword(password);
        validateRoleGrade(role, grade);
        try {
            return accounts.insert(UUID.randomUUID(), username, username.toLowerCase(Locale.ROOT),
                    passwords.encode(password), role, grade);
        } catch (DuplicateKeyException error) {
            throw ApiException.validation("用户名已被使用。");
        }
    }

    private Account update(Account before, String username, Role role, Status status, UUID grade) {
        try {
            return accounts.update(before, username, username.toLowerCase(Locale.ROOT), role, status, grade);
        } catch (DuplicateKeyException error) {
            throw ApiException.validation("用户名已被使用。");
        }
    }

    private void validateRoleGrade(Role role, UUID grade) {
        if (role == null || (role == Role.STUDENT && (grade == null || !accounts.gradeEnabled(grade)))
                || (role == Role.ADMIN && grade != null)) {
            throw ApiException.validation("角色与有效年级不匹配。");
        }
    }

    private void requireAdmin(UUID id) {
        if (current(id).role() != Role.ADMIN) throw ApiException.forbidden();
    }

    private Account target(UUID id) {
        return accounts.byId(id).filter(account -> account.status() != Status.DELETED)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "账号不存在。"));
    }

    private Account versioned(UUID id, int version) {
        Account result = accounts.lockAccount(id).filter(account -> account.status() != Status.DELETED)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "账号不存在。"));
        if (result.version() != version) throw ApiException.conflict("账号已更新，请刷新后重试。");
        return result;
    }

    private void protectLastAdmin(Account before, Role role, Status status) {
        if (before.role() == Role.ADMIN && before.status() == Status.ENABLED
                && (role != Role.ADMIN || status != Status.ENABLED) && accounts.enabledAdmins() <= 1) {
            throw ApiException.conflict("至少保留一个启用的管理员。");
        }
    }

    private static String canonicalName(String value) {
        String normalized = value == null ? "" : Normalizer.normalize(value.trim(), Normalizer.Form.NFKC);
        if (!normalized.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{2,31}")) {
            throw ApiException.validation("用户名需要 3–32 位字母、数字或允许的符号。");
        }
        return normalized;
    }

    private static void validatePassword(String password) {
        int length = password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
        if (length < 12 || length > 72) throw ApiException.validation("密码需要 12–72 个 UTF-8 字节。");
    }

    public static boolean validToken(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}");
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required", error);
        }
    }

    private static final class LoginRejected extends ApiException {
        LoginRejected(HttpStatus status, String code, String message) { super(status, code, message); }
    }
}
