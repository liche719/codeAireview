package com.codepilot.module.github.webhook;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitHubPullRequestWebhookPayload {

    private boolean ignored;

    private String reason;

    private String action;

    private String owner;

    private String repo;

    private Integer pullNumber;

    private String prUrl;

    private String title;

    public static GitHubPullRequestWebhookPayload ignored(String reason) {
        return ignored(reason, null);
    }

    public static GitHubPullRequestWebhookPayload ignored(String reason, String action) {
        GitHubPullRequestWebhookPayload payload = new GitHubPullRequestWebhookPayload();
        payload.setIgnored(true);
        payload.setReason(reason);
        payload.setAction(action);
        return payload;
    }

    public static GitHubPullRequestWebhookPayload supported(
            String action,
            String owner,
            String repo,
            Integer pullNumber,
            String prUrl,
            String title
    ) {
        GitHubPullRequestWebhookPayload payload = new GitHubPullRequestWebhookPayload();
        payload.setIgnored(false);
        payload.setAction(action);
        payload.setOwner(owner);
        payload.setRepo(repo);
        payload.setPullNumber(pullNumber);
        payload.setPrUrl(prUrl);
        payload.setTitle(StringUtils.hasText(title) ? title : null);
        return payload;
    }
}
