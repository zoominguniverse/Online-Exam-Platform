package com.onlineexam.platform.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class Assignment {
    private Assignment() {}

    public enum State { DRAFT, PUBLISHED, ARCHIVED }

    public record Draft(UUID gradeId, String name, List<UUID> paperIds) {}
    public record Task(UUID id, UUID gradeId, String name, State state, int version, List<UUID> paperIds,
                       OffsetDateTime createdAt, OffsetDateTime publishedAt) {}
    public record PaperItem(UUID paperId, UUID paperVersionId, int position, String name, int durationMinutes) {}
    public record View(Task task, List<PaperItem> papers, long recipientCount) {}
    public record StudentPaper(UUID paperId, UUID paperVersionId, int position, String name, int durationMinutes,
                               String status) {}
    public record StudentView(UUID id, String name, State state, List<StudentPaper> papers) {}
}
