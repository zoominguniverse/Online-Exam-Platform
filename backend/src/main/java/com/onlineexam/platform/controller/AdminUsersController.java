package com.onlineexam.platform.controller;

import com.onlineexam.platform.domain.Identity.AccountView;
import com.onlineexam.platform.domain.Identity.Role;
import com.onlineexam.platform.domain.Identity.Status;
import com.onlineexam.platform.repository.SessionRepository.Principal;
import com.onlineexam.platform.service.IdentityService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/admin/users")
class AdminUsersController {
    record Create(String username, String password, Role role, UUID gradeId) {}
    record Edit(String username, Role role, UUID gradeId, int expectedVersion) {}
    record Version(int expectedVersion) {}

    private final IdentityService identity;

    AdminUsersController(IdentityService identity) { this.identity = identity; }

    @GetMapping
    ApiResponse<IdentityService.Page> list(@AuthenticationPrincipal Principal admin,
            @RequestParam(required = false) Role role, @RequestParam(required = false) UUID gradeId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "createdAt") String sort) {
        return ApiResponse.ok(identity.list(admin.id(), role, gradeId, page, pageSize, sort));
    }

    @GetMapping("/{id}")
    ApiResponse<AccountView> detail(@AuthenticationPrincipal Principal admin, @PathVariable UUID id) {
        return ApiResponse.ok(identity.detail(admin.id(), id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<AccountView> create(@AuthenticationPrincipal Principal admin, @RequestBody Create form) {
        return ApiResponse.ok(identity.createManaged(admin.id(), form.username(), form.password(), form.role(), form.gradeId()));
    }

    @PatchMapping("/{id}")
    ApiResponse<AccountView> edit(@AuthenticationPrincipal Principal admin, @PathVariable UUID id, @RequestBody Edit form) {
        return ApiResponse.ok(identity.edit(admin.id(), id, form.expectedVersion(), form.username(), form.role(), form.gradeId()));
    }

    @PostMapping("/{id}/enable")
    ApiResponse<AccountView> enable(@AuthenticationPrincipal Principal admin, @PathVariable UUID id, @RequestBody Version form) {
        return ApiResponse.ok(identity.changeStatus(admin.id(), id, form.expectedVersion(), Status.ENABLED));
    }

    @PostMapping("/{id}/disable")
    ApiResponse<AccountView> disable(@AuthenticationPrincipal Principal admin, @PathVariable UUID id, @RequestBody Version form) {
        return ApiResponse.ok(identity.changeStatus(admin.id(), id, form.expectedVersion(), Status.DISABLED));
    }

    @DeleteMapping("/{id}")
    ApiResponse<AccountView> delete(@AuthenticationPrincipal Principal admin, @PathVariable UUID id, @RequestBody Version form) {
        return ApiResponse.ok(identity.changeStatus(admin.id(), id, form.expectedVersion(), Status.DELETED));
    }
}
