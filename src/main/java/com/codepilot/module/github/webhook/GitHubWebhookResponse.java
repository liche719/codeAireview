package com.codepilot.module.github.webhook;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitHubWebhookResponse {

    private Long taskId;

    private String action;

    private String reason;

    public static GitHubWebhookResponse processed(Long taskId, String action) {
        return new GitHubWebhookResponse(taskId, action, null);
    }

    public static GitHubWebhookResponse ignored(String reason) {
        return new GitHubWebhookResponse(null, null, reason);
    }

    public static GitHubWebhookResponse ignored(String reason, String action) {
        return new GitHubWebhookResponse(null, action, reason);
    }

    public boolean isIgnored() {
        return StringUtils.hasText(reason);
    }
}
