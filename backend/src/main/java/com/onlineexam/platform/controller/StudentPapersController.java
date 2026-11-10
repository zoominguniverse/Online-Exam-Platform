package com.onlineexam.platform.controller;

import com.onlineexam.platform.domain.Paper.Availability;
import com.onlineexam.platform.domain.Paper.StudentView;
import com.onlineexam.platform.domain.Paper.Type;
import com.onlineexam.platform.domain.QuestionBank.Page;
import com.onlineexam.platform.repository.SessionRepository.Principal;
import com.onlineexam.platform.service.PaperService;
import com.onlineexam.platform.service.AttemptService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/papers")
class StudentPapersController {
    private final PaperService papers;
    private final AttemptService attempts;

    StudentPapersController(PaperService papers, AttemptService attempts) {
        this.papers = papers;
        this.attempts = attempts;
    }

    @GetMapping
    ApiResponse<Page<StudentView>> list(@AuthenticationPrincipal Principal student,
            @RequestParam(required = false) Type type, @RequestParam(required = false) Availability status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(papers.listVisible(student.id(), type, status, page, pageSize));
    }

    @GetMapping("/{id}")
    ApiResponse<StudentView> detail(@AuthenticationPrincipal Principal student, @PathVariable UUID id) {
        return ApiResponse.ok(papers.visibleDetail(student.id(), id));
    }

    @PostMapping("/{id}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<AttemptService.View> start(@AuthenticationPrincipal Principal student, @PathVariable UUID id,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApiResponse.ok(attempts.start(student.id(), id, key));
    }
}
