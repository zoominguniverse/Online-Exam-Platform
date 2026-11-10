package com.onlineexam.platform.service;

import com.onlineexam.platform.domain.ApiException;
import com.onlineexam.platform.domain.Identity.Account;
import com.onlineexam.platform.domain.Identity.AccountView;
import com.onlineexam.platform.domain.Identity.Role;
import com.onlineexam.platform.domain.Identity.Session;
import com.onlineexam.platform.domain.Identity.Status;
import com.onlineexam.platform.integration.WeChatIdentityProvider;
import com.onlineexam.platform.integration.WeChatIdentityProvider.Subject;
import com.onlineexam.platform.repository.AccountRepository;
import com.onlineexam.platform.repository.WeChatRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WeChatIdentityService {
    private final WeChatIdentityProvider provider;
    private final WeChatRepository bindings;
    private final AccountRepository accounts;
    private final IdentityService identity;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public WeChatIdentityService(WeChatIdentityProvider provider, WeChatRepository bindings,
                                 AccountRepository accounts, IdentityService identity,
                                 TransactionTemplate transaction, Clock clock) {
        this.provider = provider;
        this.bindings = bindings;
        this.accounts = accounts;
        this.identity = identity;
        this.transaction = transaction;
        this.clock = clock;
    }

    public Session bind(String code, String username, String password, String ip) {
        AccountView verified = identity.authenticateStudent(username, password, ip);
        Subject subject = exchange(code);
        return transaction.execute(status -> {
            Account student = lockStudent(verified.id());
            if (student.version() != verified.version()) throw ApiException.unauthenticated();
            return createBinding(student.view(), subject);
        });
    }

    public Session register(String code, String username, String password, UUID gradeId) {
        Subject subject = exchange(code);
        return transaction.execute(status -> createBinding(identity.register(username, password, gradeId), subject));
    }

    public Session login(String code) {
        Subject subject = exchange(code);
        return transaction.execute(status -> {
            UUID user = bindings.boundUser(subject).orElseThrow(WeChatIdentityService::notBound);
            Account student = lockStudent(user);
            UUID binding = bindings.bindingForUser(user, subject).orElseThrow(WeChatIdentityService::notBound);
            return createSession(student.view(), binding);
        });
    }

    public void unlink(UUID user, UUID binding) {
        if (binding == null) throw ApiException.forbidden();
        transaction.executeWithoutResult(status -> {
            lockStudent(user);
            if (!bindings.unlink(user, binding)) throw ApiException.unauthenticated();
        });
    }

    private Subject exchange(String code) {
        if (code == null || !code.matches("[A-Za-z0-9_-]{1,128}")) {
            throw ApiException.validation("微信登录码无效，请重新获取。");
        }
        Subject subject = provider.exchange(code);
        if (!bindings.consume(IdentityService.digest(subject.appId() + ":" + code), OffsetDateTime.now(clock))) {
            throw ApiException.validation("微信登录码已使用，请重新获取。");
        }
        return subject;
    }

    private Session createBinding(AccountView student, Subject subject) {
        UUID binding = UUID.randomUUID();
        if (!bindings.bind(binding, student.id(), subject)) {
            throw new ApiException(HttpStatus.CONFLICT, "BINDING_CONFLICT", "该微信或学生账号已有绑定，请先解绑。");
        }
        return createSession(student, binding);
    }

    private Session createSession(AccountView student, UUID binding) {
        String token = identity.newToken();
        bindings.createToken(binding, IdentityService.digest(token), OffsetDateTime.now(clock));
        return new Session(token, student);
    }

    private Account lockStudent(UUID user) {
        return accounts.lockAccount(user).filter(account -> account.role() == Role.STUDENT
                        && account.status() == Status.ENABLED)
                .orElseThrow(ApiException::unauthenticated);
    }

    private static ApiException notBound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "此微信账号尚未绑定。");
    }
}
