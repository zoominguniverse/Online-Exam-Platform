package com.onlineexam.platform.controller;

import com.onlineexam.platform.domain.Identity.Session;
import com.onlineexam.platform.repository.SessionRepository.Principal;
import com.onlineexam.platform.service.WeChatIdentityService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/wechat")
class WeChatIdentityController {
    record Bind(String code, String username, String password) {}
    record Registration(String code, String username, String password, UUID gradeId) {}
    record Login(String code) {}

    private final WeChatIdentityService identity;

    WeChatIdentityController(WeChatIdentityService identity) { this.identity = identity; }

    @PostMapping("/bind")
    ApiResponse<Session> bind(@RequestBody Bind form, HttpServletRequest request) {
        return ApiResponse.ok(identity.bind(form.code(), form.username(), form.password(), request.getRemoteAddr()));
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<Session> register(@RequestBody Registration form) {
        return ApiResponse.ok(identity.register(form.code(), form.username(), form.password(), form.gradeId()));
    }

    @PostMapping("/login")
    ApiResponse<Session> login(@RequestBody Login form) {
        return ApiResponse.ok(identity.login(form.code()));
    }

    @DeleteMapping("/binding")
    ApiResponse<Void> unlink(@AuthenticationPrincipal Principal student) {
        identity.unlink(student.id(), student.bindingId());
        return ApiResponse.ok(null);
    }
}
