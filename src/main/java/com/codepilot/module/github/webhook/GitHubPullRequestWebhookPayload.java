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

    private String event;

    private String action;

    private String owner;

    private String repo;

    private Integer pullNumber;

    private String prUrl;

    private String title;

    private String headSha;

    private Long commentId;

    private String commentBody;

    private String commentUserLogin;

    public static GitHubPullRequestWebhookPayload ignored(String reason) {
        return ignored(reason, null, null);
    }

    public static GitHubPullRequestWebhookPayload ignored(String reason, String action) {
        return ignored(reason, action, null);
    }

    public static GitHubPullRequestWebhookPayload ignored(String reason, String action, String event) {
        GitHubPullRequestWebhookPayload payload = new GitHubPullRequestWebhookPayload();
        payload.setIgnored(true);
        payload.setReason(reason);
        payload.setAction(action);
        payload.setEvent(event);
        return payload;
    }

    public static GitHubPullRequestWebhookPayload supported(
            String action,
            String owner,
            String repo,
            Integer pullNumber,
            String prUrl,
            String title,
            String headSha
    ) {
        GitHubPullRequestWebhookPayload payload = new GitHubPullRequestWebhookPayload();
        payload.setIgnored(false);
        payload.setEvent("pull_request");
        payload.setAction(action);
        payload.setOwner(owner);
        payload.setRepo(repo);
        payload.setPullNumber(pullNumber);
        payload.setPrUrl(prUrl);
        payload.setTitle(StringUtils.hasText(title) ? title : null);
        payload.setHeadSha(StringUtils.hasText(headSha) ? headSha : null);
        return payload;
    }

    public static GitHubPullRequestWebhookPayload reviewCommand(
            String action,
            String owner,
            String repo,
            Integer pullNumber,
            String prUrl,
            String title,
            Long commentId,
            String commentBody,
            String commentUserLogin
    ) {
        GitHubPullRequestWebhookPayload payload = new GitHubPullRequestWebhookPayload();
        payload.setIgnored(false);
        payload.setEvent("issue_comment");
        payload.setAction(action);
        payload.setOwner(owner);
        payload.setRepo(repo);
        payload.setPullNumber(pullNumber);
        payload.setPrUrl(prUrl);
        payload.setTitle(StringUtils.hasText(title) ? title : null);
        payload.setCommentId(commentId);
        payload.setCommentBody(StringUtils.hasText(commentBody) ? commentBody : null);
        payload.setCommentUserLogin(StringUtils.hasText(commentUserLogin) ? commentUserLogin : null);
        return payload;
    }
}
