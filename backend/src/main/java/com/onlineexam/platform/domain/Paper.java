package com.onlineexam.platform.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class Paper {
    private Paper() {}

    public enum Type { FIXED, TIMED, TASK }
    public enum Availability { OPEN, UPCOMING, CLOSED }

    public record Head(UUID id, UUID subjectId, Type type, int version, UUID draftId, UUID publishedId,
                       boolean archived, OffsetDateTime createdAt) {}
    public record Summary(UUID id, UUID subjectId, Type type, String name, int version,
                          UUID draftId, UUID publishedId, boolean archived) {}
    public record Draft(String name, int durationMinutes, OffsetDateTime opensAt,
                        OffsetDateTime closesAt, List<Group> groups) {}
    public record Group(String name, List<UUID> questionIds) {}
    public record Snapshot(UUID id, int revision, String name, int durationMinutes, OffsetDateTime opensAt,
                           OffsetDateTime closesAt, OffsetDateTime publishedAt) {}
    public record Item(int position, int groupPosition, String groupName, UUID questionId,
                       int questionVersion, int scoreMinor) {}
    public record StudentView(UUID id, UUID subjectId, Type type, String name, int durationMinutes,
                              OffsetDateTime opensAt, OffsetDateTime closesAt, Availability status,
                              OffsetDateTime serverNow) {}
    public record View(Head paper, Snapshot content, List<ItemView> items, String totalScore) {
        public static View of(Head paper, Snapshot content, List<Item> items) {
            return new View(paper, content, items.stream().map(ItemView::of).toList(),
                    score(items.stream().mapToLong(Item::scoreMinor).sum()));
        }
    }
    public record ItemView(int position, int groupPosition, String groupName, UUID questionId,
                           int questionVersion, String score) {
        static ItemView of(Item item) {
            return new ItemView(item.position(), item.groupPosition(), item.groupName(),
                    item.questionId(), item.questionVersion(), Paper.score(item.scoreMinor()));
        }
    }
    public static String score(long minor) { return BigDecimal.valueOf(minor, 2).toPlainString(); }
}
