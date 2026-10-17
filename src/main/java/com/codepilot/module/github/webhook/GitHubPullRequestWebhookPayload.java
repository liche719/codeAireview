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

    /** 用户在 diff 某一行上提问时的文件路径（仅 pull_request_review_comment 事件有值）。 */
    private String anchorPath;

    /** 用户在 diff 某一行上提问时的行号。 */
    private Integer anchorLine;

    /**
     * 回复时应投递到的评论 id。
     *
     * <p>{@code pull_request_review_comment} 事件里，若本条评论本身就是某条评论的回复，
     * 这里存的是<b>线程根</b>（{@code in_reply_to_id}）——GitHub 的 replies 接口要求传线程根，
     * 传一条回复的 id 会失败。顶层评论则用它自己的 id。</p>
     */
    private Long replyToCommentId;

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

    /**
     * diff 行上的 @mention（{@code pull_request_review_comment}）。
     *
     * <p>与 {@link #reviewCommand} 的区别只有两点：事件名不同，以及多了
     * 「锚定在哪一行」与「回复投递到哪个线程」。命令解析、作者判定、去重键的语义完全一致。</p>
     */
    public static GitHubPullRequestWebhookPayload reviewCommentCommand(
            String action,
            String owner,
            String repo,
            Integer pullNumber,
            String prUrl,
            String title,
            String headSha,
            Long commentId,
            String commentBody,
            String commentUserLogin,
            String commentAuthorAssociation,
            String anchorPath,
            Integer anchorLine,
            Long replyToCommentId,
            String commandType,
            String commandText,
            boolean mentionedBot,
            boolean dryRun
    ) {
        GitHubPullRequestWebhookPayload payload = new GitHubPullRequestWebhookPayload();
        payload.setIgnored(false);
        payload.setEvent("pull_request_review_comment");
        payload.setAction(action);
        payload.setOwner(owner);
        payload.setRepo(repo);
        payload.setPullNumber(pullNumber);
        payload.setPrUrl(prUrl);
        payload.setTitle(StringUtils.hasText(title) ? title : null);
        payload.setHeadSha(StringUtils.hasText(headSha) ? headSha : null);
        payload.setCommentId(commentId);
        payload.setCommentBody(StringUtils.hasText(commentBody) ? commentBody : null);
        payload.setCommentUserLogin(StringUtils.hasText(commentUserLogin) ? commentUserLogin : null);
        payload.setCommentAuthorAssociation(StringUtils.hasText(commentAuthorAssociation) ? commentAuthorAssociation : null);
        payload.setAnchorPath(StringUtils.hasText(anchorPath) ? anchorPath : null);
        payload.setAnchorLine(anchorLine);
        payload.setReplyToCommentId(replyToCommentId);
        payload.setCommandType(commandType);
        payload.setCommandText(StringUtils.hasText(commandText) ? commandText : null);
        payload.setMentionedBot(mentionedBot);
        payload.setDryRun(dryRun);
        return payload;
    }
}
