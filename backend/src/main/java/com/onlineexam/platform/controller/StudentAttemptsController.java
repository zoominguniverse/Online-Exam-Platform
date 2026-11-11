package com.onlineexam.platform.controller;

import com.onlineexam.platform.repository.SessionRepository.Principal;
import com.onlineexam.platform.service.AttemptService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/attempts")
class StudentAttemptsController {
    private final AttemptService attempts;

    StudentAttemptsController(AttemptService attempts) { this.attempts = attempts; }

    @GetMapping("/{id}")
    ApiResponse<AttemptService.View> read(@AuthenticationPrincipal Principal student, @PathVariable UUID id) {
        return ApiResponse.ok(attempts.read(student.id(), id));
    }

    @PutMapping("/{id}/answers")
    ApiResponse<AttemptService.View> save(@AuthenticationPrincipal Principal student, @PathVariable UUID id,
                                           @RequestBody JsonNode body) {
        return ApiResponse.ok(attempts.save(student.id(), id, body));
    }

    @PostMapping("/{id}/submit")
    ApiResponse<AttemptService.View> submit(@AuthenticationPrincipal Principal student, @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        return ApiResponse.ok(attempts.submit(student.id(), id, key, body));
    }
}
