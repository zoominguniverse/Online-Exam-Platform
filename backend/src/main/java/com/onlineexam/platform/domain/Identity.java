package com.onlineexam.platform.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class Identity {
    private Identity() {}

    public enum Role { STUDENT, ADMIN }
    public enum Status { ENABLED, DISABLED, DELETED }

    public record Grade(UUID id, String name) {}
    public record Account(UUID id, String username, String passwordHash, Role role, Status status,
                          UUID gradeId, int version, OffsetDateTime createdAt) {
        public AccountView view() {
            return new AccountView(id, username, role, status, gradeId, version, createdAt);
        }
    }
    public record AccountView(UUID id, String username, Role role, Status status, UUID gradeId,
                              int version, OffsetDateTime createdAt) {}
    public record Session(String token, AccountView user) {}
}
