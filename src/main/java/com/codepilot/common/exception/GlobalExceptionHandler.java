package com.codepilot.common.exception;

import com.codepilot.common.response.Result;
import com.codepilot.common.util.SensitiveDataSanitizer;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
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

    /**
     * HTTP 方法不匹配（例如用浏览器 GET 打开只接受 POST 的 webhook 地址）。
     *
     * <p>之前这种情况落到 {@link #handleException}，返回 **HTTP 200 +
     * {@code {"code":500,"message":"server error"}}** 并记一条
     * {@code Unhandled server exception} 的 ERROR 日志——三处都不对：
     * 状态码该是 405、文案该说清方法不对、日志也不该记成服务端错误。</p>
     *
     * <p><b>为什么必须放在全局而不是 webhook 控制器里</b>：方法不匹配是在
     * <b>匹配到处理器之前</b>抛出的，此时还没有控制器实例，
     * 控制器内的 {@code @ExceptionHandler} 不会被调用（已用测试确认）。</p>
     *
     * <p>这确实偏离了本项目"HTTP 200 + 业务 code"的封装约定，但只针对**协议层**错误：
     * 方法/媒体类型这类问题不是业务结果，按 HTTP 语义回状态码对客户端和监控都更正确。</p>
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception) {
        log.info("Request rejected because the HTTP method is not supported, method={}, supported={}",
                exception.getMethod(), exception.getSupportedHttpMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail(405, "HTTP method not supported for this endpoint"));
    }

    private String safeMessage(String message, String fallback) {
        String sanitized = SensitiveDataSanitizer.redact(message);
        return org.springframework.util.StringUtils.hasText(sanitized) ? sanitized : fallback;
    }
}
