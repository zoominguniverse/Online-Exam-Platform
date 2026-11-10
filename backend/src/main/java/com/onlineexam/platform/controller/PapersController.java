package com.onlineexam.platform.controller;

import com.onlineexam.platform.domain.Paper.Draft;
import com.onlineexam.platform.domain.Paper.Summary;
import com.onlineexam.platform.domain.Paper.Type;
import com.onlineexam.platform.domain.Paper.View;
import com.onlineexam.platform.domain.QuestionBank.Page;
import com.onlineexam.platform.service.PaperService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/admin/papers")
class PapersController {
    record Create(UUID subjectId, Type type, Draft draft) {}
    record Edit(int expectedVersion, Draft draft) {}
    record Version(int expectedVersion) {}

    private final PaperService papers;

    PapersController(PaperService papers) { this.papers = papers; }

    @GetMapping
    ApiResponse<Page<Summary>> list(@RequestParam(required = false) UUID subjectId, @RequestParam(required = false) UUID gradeId,
            @RequestParam(required = false) Type type, @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(papers.list(subjectId, gradeId, type, includeArchived, page, pageSize));
    }

    @GetMapping("/{id}")
    ApiResponse<View> detail(@PathVariable UUID id, @RequestParam(required = false) UUID versionId) {
        return ApiResponse.ok(papers.detail(id, versionId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<View> create(@RequestBody Create form) {
        return ApiResponse.ok(papers.create(form.subjectId(), form.type(), form.draft()));
    }

    @PatchMapping("/{id}")
    ApiResponse<View> edit(@PathVariable UUID id, @RequestBody Edit form) {
        return ApiResponse.ok(papers.edit(id, form.expectedVersion(), form.draft()));
    }

    @PostMapping("/{id}/publish")
    ApiResponse<View> publish(@PathVariable UUID id, @RequestBody Version form) {
        return ApiResponse.ok(papers.publish(id, form.expectedVersion()));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@PathVariable UUID id, @RequestBody Version form) {
        papers.delete(id, form.expectedVersion());
        return ApiResponse.ok(null);
    }
}
