package com.onlineexam.platform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.onlineexam.platform.domain.ApiException;
import com.onlineexam.platform.repository.SessionRepository.Principal;
import com.onlineexam.platform.service.*;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
class CommunityController {
    private final CommunityService community;private final AttachmentService attachments;
    private final ObservabilityService operations;
    CommunityController(CommunityService community,AttachmentService attachments,ObservabilityService operations) { this.community=community;this.attachments=attachments;this.operations=operations; }
    @GetMapping("/me/profile") Object profile(@AuthenticationPrincipal Principal user) { return ApiResponse.ok(community.profile(user.id())); }
    @PatchMapping("/me/profile") Object profile(@AuthenticationPrincipal Principal user,@RequestBody JsonNode body) { return ApiResponse.ok(community.updateProfile(user.id(),body)); }
    @PostMapping("/me/avatar") Object avatar(@AuthenticationPrincipal Principal user,@RequestParam MultipartFile file) {
        try { var image=attachments.stage(user.id(),file.getBytes());return ApiResponse.ok(community.avatar(user.id(),image.id())); }
        catch(java.io.IOException error) { throw ApiException.validation("无法读取图片。"); }
    }
    @GetMapping("/admin/messages") Object sent(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return ApiResponse.ok(community.messages(null,page,pageSize)); }
    @GetMapping("/admin/messages/{id}") Object sent(@PathVariable UUID id) { return ApiResponse.ok(community.sent(id)); }
    @PostMapping("/admin/messages") Object send(@AuthenticationPrincipal Principal user,@RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) { return ApiResponse.ok(community.send(user.id(),key,body)); }
    @GetMapping("/me/messages") Object inbox(@AuthenticationPrincipal Principal user,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize) { return ApiResponse.ok(community.messages(user.id(),page,pageSize)); }
    @GetMapping("/me/messages/{id}") Object inbox(@AuthenticationPrincipal Principal user,@PathVariable UUID id) { return ApiResponse.ok(community.inbox(user.id(),id)); }
    @PutMapping("/me/messages/{id}/read") Object read(@AuthenticationPrincipal Principal user,@PathVariable UUID id) { return ApiResponse.ok(community.markRead(user.id(),id)); }
    @GetMapping("/me/activities") Object activities(@AuthenticationPrincipal Principal user,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize) { return ApiResponse.ok(community.events(user.id(),null,null,page,pageSize)); }
    @GetMapping("/admin/audit-events") Object events(@RequestParam(required=false) UUID actorId,@RequestParam(required=false) UUID requestId,
            @RequestParam(required=false) String action,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return ApiResponse.ok(community.events(actorId,requestId,action,page,pageSize));
    }
    @GetMapping("/admin/statistics") Object statistics() { return ApiResponse.ok(community.statistics()); }
    @GetMapping("/admin/observability") Object metrics() { return ApiResponse.ok(operations.metrics()); }
}
