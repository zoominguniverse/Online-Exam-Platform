package com.onlineexam.platform.domain;

import org.springframework.http.HttpStatus;

// A due-answer save commits its deadline freeze even though the late payload is rejected.
public class ExamClosedException extends ApiException {
    public ExamClosedException() { super(HttpStatus.CONFLICT, "EXAM_CLOSED", "答卷已截止或不可编辑；未同步答案未计入。"); }
}
