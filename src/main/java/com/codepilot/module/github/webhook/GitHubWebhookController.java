package com.codepilot.module.github.webhook;

import com.codepilot.common.response.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
