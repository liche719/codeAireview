package com.codepilot.module.github.webhook;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.response.Result;
import com.codepilot.common.util.SensitiveDataSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/github/webhook")
public class GitHubWebhookController {

    private final GitHubWebhookService gitHubWebhookService;

    @PostMapping
    public Result<GitHubWebhookResponse> handleWebhook(
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String delivery,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody(required = false) String payload
    ) {
        GitHubWebhookResponse response = gitHubWebhookService.handle(event, delivery, signature, payload);
        if (response.isIgnored()) {
            return new Result<>(0, "ignored", response);
        }
        return Result.success(response);
    }

    /**
     * webhook 的异常必须映射成**真实的 HTTP 状态码**，不能沿用全局处理器那种
     * "HTTP 200 + body 里 code=401" 的封装。
     *
     * <p>原因很实际：<b>GitHub 是按 HTTP 状态码决定要不要重投的。</b>
     * 签名不匹配若回 200，GitHub 会认为投递成功、永不重试；
     * 任何基于状态码的监控也会把它当成正常流量。</p>
     *
     * <p>只在**本控制器内**覆盖：其余 JSON 接口的约定是"HTTP 200 + 业务 code"，
     * 全局改动会破坏调用方。</p>
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleWebhookBusinessException(BusinessException exception) {
        Integer code = exception.getCode();
        int status = code != null && code >= 400 && code <= 599 ? code : 500;
        String message = SensitiveDataSanitizer.redact(exception.getMessage());
        if (!StringUtils.hasText(message)) {
            message = "webhook request failed";
        }
        if (status >= 500) {
            log.error("GitHub webhook failed, status={}, errorType={}, message={}",
                    status, exception.getClass().getSimpleName(), message);
        } else {
            log.warn("GitHub webhook rejected, status={}, message={}", status, message);
        }
        return ResponseEntity.status(status).body(Result.fail(code, message));
    }
}
