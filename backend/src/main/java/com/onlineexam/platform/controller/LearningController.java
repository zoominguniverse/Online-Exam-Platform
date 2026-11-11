package com.onlineexam.platform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.onlineexam.platform.repository.SessionRepository.Principal;
import com.onlineexam.platform.service.LearningService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class LearningController {
    private final LearningService learning;
    private final com.onlineexam.platform.service.SubjectService subjects;
    private final com.onlineexam.platform.service.IdentityService identity;
    LearningController(LearningService learning,com.onlineexam.platform.service.SubjectService subjects,com.onlineexam.platform.service.IdentityService identity) { this.learning=learning;this.subjects=subjects;this.identity=identity; }
    @GetMapping("/me/subjects")
    Object subjects(@AuthenticationPrincipal Principal user,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return ApiResponse.ok(subjects.list(identity.current(user.id()).gradeId(),page,pageSize));
    }
    @GetMapping("/me/attempts")
    Object records(@AuthenticationPrincipal Principal user,@RequestParam(required=false) UUID subjectId,
            @RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="startedAt") String sort) {
        return ApiResponse.ok(learning.list(user.id(),subjectId,status,page,pageSize,sort));
    }
    @GetMapping("/admin/attempts")
    Object adminRecords(@RequestParam(required=false) UUID ownerId,@RequestParam(required=false) UUID subjectId,
            @RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="startedAt") String sort) {
        return ApiResponse.ok(learning.list(ownerId,subjectId,status,page,pageSize,sort));
    }
    @GetMapping("/admin/attempts/{id}")
    Object adminReview(@PathVariable UUID id) { return ApiResponse.ok(learning.adminReview(id)); }
    @GetMapping("/me/attempts/{id}/review")
    Object review(@AuthenticationPrincipal Principal user,@PathVariable UUID id) { return ApiResponse.ok(learning.review(user.id(),id)); }
    @PostMapping("/me/attempts/{id}/self-grade")
    Object confirm(@AuthenticationPrincipal Principal user,@PathVariable UUID id,@RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) { return ApiResponse.ok(learning.confirm(user.id(),id,key,body)); }
    @GetMapping("/me/mistakes")
    Object mistakes(@AuthenticationPrincipal Principal user,@RequestParam(required=false) UUID subjectId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return ApiResponse.ok(learning.mistakes(user.id(),subjectId,page,pageSize));
    }
    @GetMapping("/me/mistakes/{id}")
    Object mistake(@AuthenticationPrincipal Principal user,@PathVariable UUID id) { return ApiResponse.ok(learning.mistake(user.id(),id)); }
}
