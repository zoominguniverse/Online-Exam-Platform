package com.onlineexam.platform.controller;

import com.onlineexam.platform.domain.ApiException;
import com.onlineexam.platform.repository.SessionRepository.Principal;
import com.onlineexam.platform.service.AttachmentService;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
class AttachmentsController {
    private final AttachmentService attachments;

    AttachmentsController(AttachmentService attachments) { this.attachments = attachments; }

    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<AttachmentService.Staged> upload(@AuthenticationPrincipal Principal principal,
                                                 @RequestParam MultipartFile file) {
        try {
            return ApiResponse.ok(attachments.stage(principal.id(), file.getBytes()));
        } catch (IOException error) {
            throw ApiException.validation("无法读取上传的图片。");
        }
    }

    @GetMapping("/attachments/{id}")
    ResponseEntity<byte[]> read(@AuthenticationPrincipal Principal principal, @PathVariable UUID id) {
        return reply(attachments.readOwned(id, principal.id()));
    }

    @GetMapping("/admin/questions/{question}/versions/{version}/attachments/{id}")
    ResponseEntity<byte[]> readQuestionImage(@PathVariable UUID question, @PathVariable int version, @PathVariable UUID id) {
        return reply(attachments.readQuestionImage(id, question, version));
    }

    private static ResponseEntity<byte[]> reply(AttachmentService.Image image) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.noStore()).body(image.bytes());
    }
}
