package com.onlineexam.platform.controller;

import com.onlineexam.platform.domain.Assignment.Draft;
import com.onlineexam.platform.domain.Assignment.State;
import com.onlineexam.platform.domain.Assignment.StudentView;
import com.onlineexam.platform.domain.Assignment.Task;
import com.onlineexam.platform.domain.Assignment.View;
import com.onlineexam.platform.domain.QuestionBank.Page;
import com.onlineexam.platform.repository.SessionRepository.Principal;
import com.onlineexam.platform.service.AssignmentService;
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
@RequestMapping("/api/v1")
class AssignmentController {
    record Edit(int expectedVersion, Draft draft) {}
    record Version(int expectedVersion) {}

    private final AssignmentService tasks;

    AssignmentController(AssignmentService tasks) { this.tasks = tasks; }

    @GetMapping("/admin/tasks")
    ApiResponse<Page<Task>> list(@RequestParam(required = false) UUID gradeId,
            @RequestParam(required = false) State state, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(tasks.list(gradeId, state, page, pageSize));
    }

    @GetMapping("/admin/tasks/{id}")
    ApiResponse<View> detail(@PathVariable UUID id) { return ApiResponse.ok(tasks.detail(id)); }

    @PostMapping("/admin/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<Task> create(@RequestBody Draft form) { return ApiResponse.ok(tasks.create(form)); }

    @PatchMapping("/admin/tasks/{id}")
    ApiResponse<Task> edit(@PathVariable UUID id, @RequestBody Edit form) {
        return ApiResponse.ok(tasks.edit(id, form.expectedVersion(), form.draft()));
    }

    @PostMapping("/admin/tasks/{id}/publish")
    ApiResponse<View> publish(@PathVariable UUID id, @RequestBody Version form) {
        return ApiResponse.ok(tasks.publish(id, form.expectedVersion()));
    }

    @DeleteMapping("/admin/tasks/{id}")
    ApiResponse<Void> delete(@PathVariable UUID id, @RequestBody Version form) {
        tasks.delete(id, form.expectedVersion());
        return ApiResponse.ok(null);
    }

    @GetMapping("/me/tasks")
    ApiResponse<Page<StudentView>> mine(@AuthenticationPrincipal Principal student,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(tasks.listForStudent(student.id(), page, pageSize));
    }

    @GetMapping("/me/tasks/{id}")
    ApiResponse<StudentView> mine(@AuthenticationPrincipal Principal student, @PathVariable UUID id) {
        return ApiResponse.ok(tasks.detailForStudent(student.id(), id));
    }
}
