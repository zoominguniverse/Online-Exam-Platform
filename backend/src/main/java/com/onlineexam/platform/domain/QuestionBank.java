package com.onlineexam.platform.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class QuestionBank {
    private QuestionBank() {}

    public enum Type { SINGLE, MULTIPLE, TRUE_FALSE, GAP, SHORT }

    public record Subject(UUID id, UUID gradeId, String name, int version, OffsetDateTime createdAt) {}
    public record Option(String id, String text) {}
    public record Answer(List<String> optionIds, List<String> gaps, String text) {}
    public record Draft(UUID subjectId, Type type, int difficulty, String score, String stemHtml,
                        List<Option> options, Answer answer, String explanationHtml) {}
    public record Version(UUID id, int version, UUID subjectId, Type type, int difficulty, String score,
                          String stemHtml, List<Option> options, Answer answer, String explanationHtml,
                          boolean archived, OffsetDateTime createdAt) {}
    public record Summary(UUID id, int version, UUID subjectId, Type type, int difficulty, String score,
                          String stemPreview, OffsetDateTime createdAt) {}
    public record Page<T>(List<T> items, long total, int page, int pageSize) {}
}
