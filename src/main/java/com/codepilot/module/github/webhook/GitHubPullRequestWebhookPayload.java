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

    private String commentAuthorAssociation;

    private String commandType;

    private String commandText;

    private Boolean mentionedBot;

    private Boolean dryRun;

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
            String commentUserLogin,
            String commentAuthorAssociation,
            String commandType,
            String commandText,
            boolean mentionedBot,
            boolean dryRun
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
        payload.setCommentAuthorAssociation(StringUtils.hasText(commentAuthorAssociation) ? commentAuthorAssociation : null);
        payload.setCommandType(commandType);
        payload.setCommandText(StringUtils.hasText(commandText) ? commandText : null);
        payload.setMentionedBot(mentionedBot);
        payload.setDryRun(dryRun);
        return payload;
    }
}
