package com.onlineexam.platform.service;

import com.onlineexam.platform.domain.ApiException;
import com.onlineexam.platform.domain.QuestionBank.Page;
import com.onlineexam.platform.domain.QuestionBank.Subject;
import com.onlineexam.platform.repository.AccountRepository;
import com.onlineexam.platform.repository.SubjectRepository;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubjectService {
    private final SubjectRepository subjects;
    private final AccountRepository accounts;

    public SubjectService(SubjectRepository subjects, AccountRepository accounts) {
        this.subjects = subjects;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<Subject> list(UUID gradeId, int page, int pageSize) {
        checkPage(page, pageSize);
        return new Page<>(subjects.list(gradeId, pageSize, (long) (page - 1) * pageSize),
                subjects.count(gradeId), page, pageSize);
    }

    public Subject get(UUID id) {
        return subjects.get(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "学科不存在。"));
    }

    @Transactional
    public Subject create(UUID gradeId, String input) {
        if (gradeId == null || !accounts.gradeEnabled(gradeId)) throw ApiException.validation("gradeId 必须是启用年级。");
        String name = clean(input);
        try {
            return subjects.insert(gradeId, name, name.toLowerCase(Locale.ROOT));
        } catch (DuplicateKeyException error) {
            throw ApiException.validation("name 在同一年级内已存在。");
        }
    }

    @Transactional
    public Subject edit(UUID id, int version, String input) {
        requireVersion(version);
        String name = clean(input);
        try {
            return subjects.update(id, version, name, name.toLowerCase(Locale.ROOT)).orElseThrow(() -> stale(id));
        } catch (DuplicateKeyException error) {
            throw ApiException.validation("name 在同一年级内已存在。");
        }
    }

    @Transactional
    public void delete(UUID id, int version) {
        requireVersion(version);
        try {
            if (subjects.delete(id, version) == 0) throw stale(id);
        } catch (DataIntegrityViolationException error) {
            if (error.getMostSpecificCause() instanceof SQLException sql && "23503".equals(sql.getSQLState())) {
                throw ApiException.conflict("学科仍被题目或试卷引用。");
            }
            throw error;
        }
    }

    private ApiException stale(UUID id) {
        get(id);
        return ApiException.conflict("学科已更新，请刷新后重试。");
    }

    private static String clean(String input) {
        String value = input == null ? "" : Normalizer.normalize(input, Normalizer.Form.NFKC).strip();
        if (value.isBlank() || value.codePointCount(0, value.length()) > 80
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.validation("name 需要 1–80 个可显示字符。");
        }
        return value;
    }

    static void checkPage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw ApiException.validation("页码或页大小无效。");
    }

    static void requireVersion(int version) {
        if (version < 1) throw ApiException.validation("expectedVersion 必须是正整数。");
    }
}
