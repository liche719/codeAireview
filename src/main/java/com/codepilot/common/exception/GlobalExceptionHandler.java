package com.codepilot.common.exception;

import com.codepilot.common.response.Result;
import com.codepilot.common.util.SensitiveDataSanitizer;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException exception) {
        return Result.fail(exception.getCode(), safeMessage(exception.getMessage(), "request failed"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("request parameter is invalid");
        return Result.fail(400, safeMessage(message, "request parameter is invalid"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolationException(ConstraintViolationException exception) {
        return Result.fail(400, safeMessage(exception.getMessage(), "request parameter is invalid"));
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception exception) {
        log.error("Unhandled server exception, errorType={}, message={}",
                exception.getClass().getSimpleName(), SensitiveDataSanitizer.redact(exception.getMessage()));
        return Result.fail(500, "server error");
    }

    private String safeMessage(String message, String fallback) {
        String sanitized = SensitiveDataSanitizer.redact(message);
        return org.springframework.util.StringUtils.hasText(sanitized) ? sanitized : fallback;
    }
}
