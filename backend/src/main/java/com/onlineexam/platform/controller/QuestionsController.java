package com.onlineexam.platform.controller;

import com.onlineexam.platform.domain.QuestionBank.Draft;
import com.onlineexam.platform.domain.QuestionBank.Page;
import com.onlineexam.platform.domain.QuestionBank.Summary;
import com.onlineexam.platform.domain.QuestionBank.Type;
import com.onlineexam.platform.domain.QuestionBank.Version;
import com.onlineexam.platform.service.QuestionService;
import com.onlineexam.platform.repository.SessionRepository.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/questions")
class QuestionsController {
    record Revision(int expectedVersion) {}
    record Edit(Draft question, int expectedVersion) {}

    private final QuestionService questions;

    QuestionsController(QuestionService questions) { this.questions = questions; }

    @GetMapping
    ApiResponse<Page<Summary>> list(@RequestParam(required = false) UUID subjectId, @RequestParam(required = false) Type type,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(questions.list(subjectId, type, page, pageSize));
    }

    @GetMapping("/{id}")
    ApiResponse<Version> detail(@PathVariable UUID id, @RequestParam(required = false) Integer version) {
        return ApiResponse.ok(questions.get(id, version));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<Version> create(@AuthenticationPrincipal Principal principal, @RequestBody Draft form) {
        return ApiResponse.ok(questions.create(principal.id(), form));
    }

    @PatchMapping("/{id}")
    ApiResponse<Version> edit(@AuthenticationPrincipal Principal principal, @PathVariable UUID id, @RequestBody Edit form) {
        return ApiResponse.ok(questions.edit(principal.id(), id, form.expectedVersion(), form.question()));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> archive(@PathVariable UUID id, @RequestBody Revision form) {
        questions.archive(id, form.expectedVersion());
        return ApiResponse.ok(null);
    }
}
