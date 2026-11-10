package com.onlineexam.platform.controller;

import com.onlineexam.platform.domain.QuestionBank.Page;
import com.onlineexam.platform.domain.QuestionBank.Subject;
import com.onlineexam.platform.service.SubjectService;
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
@RequestMapping("/api/v1/admin/subjects")
class SubjectsController {
    record Create(UUID gradeId, String name) {}
    record Edit(String name, int expectedVersion) {}
    record Version(int expectedVersion) {}

    private final SubjectService subjects;

    SubjectsController(SubjectService subjects) { this.subjects = subjects; }

    @GetMapping
    ApiResponse<Page<Subject>> list(@RequestParam(required = false) UUID gradeId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(subjects.list(gradeId, page, pageSize));
    }

    @GetMapping("/{id}")
    ApiResponse<Subject> detail(@PathVariable UUID id) { return ApiResponse.ok(subjects.get(id)); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<Subject> create(@RequestBody Create form) {
        return ApiResponse.ok(subjects.create(form.gradeId(), form.name()));
    }

    @PatchMapping("/{id}")
    ApiResponse<Subject> edit(@PathVariable UUID id, @RequestBody Edit form) {
        return ApiResponse.ok(subjects.edit(id, form.expectedVersion(), form.name()));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@PathVariable UUID id, @RequestBody Version form) {
        subjects.delete(id, form.expectedVersion());
        return ApiResponse.ok(null);
    }
}
