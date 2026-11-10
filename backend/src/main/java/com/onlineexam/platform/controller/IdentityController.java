package com.onlineexam.platform.controller;

import com.onlineexam.platform.domain.Identity.AccountView;
import com.onlineexam.platform.domain.Identity.Grade;
import com.onlineexam.platform.repository.GradeMapper;
import com.onlineexam.platform.repository.SessionRepository.Principal;
import com.onlineexam.platform.security.WebSession;
import com.onlineexam.platform.service.IdentityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
class IdentityController {
    record Registration(String username, String password, UUID gradeId) {}
    record Credentials(String username, String password) {}
    record PasswordChange(String currentPassword, String newPassword) {}
    record Csrf(String token, String headerName) {}

    private final IdentityService identity;
    private final GradeMapper grades;
    private final CookieCsrfTokenRepository csrf;

    IdentityController(IdentityService identity, GradeMapper grades, CookieCsrfTokenRepository csrf) {
        this.identity = identity;
        this.grades = grades;
        this.csrf = csrf;
    }

    @GetMapping("/catalog/grades")
    ApiResponse<List<Grade>> grades() {
        return ApiResponse.ok(grades.listEnabled());
    }

    @GetMapping("/auth/csrf")
    ApiResponse<Csrf> csrf(CsrfToken token) {
        return ApiResponse.ok(new Csrf(token.getToken(), token.getHeaderName()));
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<AccountView> register(@RequestBody Registration form) {
        return ApiResponse.ok(identity.register(form.username(), form.password(), form.gradeId()));
    }

    @PostMapping("/auth/login")
    ApiResponse<AccountView> login(@RequestBody Credentials form, HttpServletRequest request,
                                   HttpServletResponse response) {
        var session = identity.login(form.username(), form.password(), request.getRemoteAddr(), WebSession.token(request));
        response.addHeader(HttpHeaders.SET_COOKIE, WebSession.cookie(session.token()).toString());
        csrf.saveToken(null, request, response);
        return ApiResponse.ok(session.user());
    }

    @PostMapping("/auth/logout")
    ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        identity.revoke(WebSession.token(request));
        clearSession(request, response);
        return ApiResponse.ok(null);
    }

    @PostMapping("/me/password")
    ApiResponse<Void> changePassword(@AuthenticationPrincipal Principal principal, @RequestBody PasswordChange form,
                                     HttpServletRequest request, HttpServletResponse response) {
        identity.changePassword(principal.id(), form.currentPassword(), form.newPassword());
        clearSession(request, response);
        return ApiResponse.ok(null);
    }

    private void clearSession(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, WebSession.cookie(null).toString());
        csrf.saveToken(null, request, response);
    }

    @GetMapping("/me")
    ApiResponse<AccountView> me(@AuthenticationPrincipal Principal principal) {
        return ApiResponse.ok(identity.current(principal.id()));
    }
}
