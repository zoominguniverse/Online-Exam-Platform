package com.onlineexam.platform.controller;

import com.onlineexam.platform.domain.ApiException;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
class ApiErrorAdvice {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ApiErrorAdvice.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResponse<Void>> handle(ApiException error) {
        return ResponseEntity.status(error.status()).body(ApiResponse.error(error.code(), error.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestValueException.class,
            MethodArgumentTypeMismatchException.class,org.springframework.web.HttpMediaTypeNotSupportedException.class})
    ResponseEntity<ApiResponse<Void>> invalid() {
        return handle(ApiException.validation("请求字段缺失或格式错误。"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiResponse<Void>> tooLarge() {
        return handle(ApiException.validation("上传图片不能超过 5 MiB。"));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiResponse<Void>> unavailable(DataAccessException error) {
        var cause=error.getMostSpecificCause();
        log.atWarn()
            .addKeyValue("result",cause instanceof java.sql.SQLException sql?sql.getSQLState():cause.getClass().getSimpleName())
            .log("Database request failed");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("DEPENDENCY_UNAVAILABLE", "数据服务暂不可用。"));
    }
}
