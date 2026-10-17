package com.codepilot.module.github.webhook;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.dto.GithubCommand;
import com.codepilot.module.command.parser.GithubCommandParser;
import com.codepilot.module.review.report.CommentMarkers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

@Component
public class GitHubWebhookPayloadParser {

    private static final String PULL_REQUEST_EVENT = "pull_request";

    private static final String ISSUE_COMMENT_EVENT = "issue_comment";

    private static final String REVIEW_COMMENT_EVENT = "pull_request_review_comment";

    private static final Set<String> SUPPORTED_ACTIONS = Set.of("opened", "synchronize", "reopened");

    private final ObjectMapper objectMapper;

    private final GithubCommandParser commandParser;

    /**
     * 审查摘要的身份标记（可配置）。用于识别"这条评论是机器人自己发的"。
     * 单元测试手工构造本类时为 null，{@link CommentMarkers#containsAnyIdentity} 会安全跳过。
     */
    @Value("${codepilot.github.comment-marker:}")
    private String summaryMarker;

    /**
     * 是否处理 diff 行评论里的 @mention（R14）。默认开启——这是读侧能力。
     *
     * <p>关闭后 {@code pull_request_review_comment} 回到引入本能力之前的行为（被忽略）。</p>
     */
    private boolean reviewCommentMentionEnabled = true;

    /**
     * 两参重载：默认开启 diff 行 mention。手工构造本类的单元测试走这条。
     */
    public GitHubWebhookPayloadParser(ObjectMapper objectMapper, GithubCommandParser commandParser) {
        this(objectMapper, commandParser, null);
    }

    /**
     * {@code @Autowired} 必须显式标注：本类有多个构造器，Spring 无法自行选择，
     * 否则会退化成"找不到无参构造器"而在启动时失败（R10 踩过一次）。
     */
    @Autowired
    public GitHubWebhookPayloadParser(
            ObjectMapper objectMapper,
            GithubCommandParser commandParser,
            GithubCommandProperties commandProperties
    ) {
        this.objectMapper = objectMapper;
        this.commandParser = commandParser;
        this.reviewCommentMentionEnabled = commandProperties == null
                || commandProperties.isReviewCommentMentionEnabled();
    }

    public GitHubPullRequestWebhookPayload parse(String event, String payload) {
        if (PULL_REQUEST_EVENT.equals(event)) {
            return parsePullRequestEvent(payload);
        }
        if (ISSUE_COMMENT_EVENT.equals(event)) {
            return parseIssueCommentEvent(payload);
        }
        if (REVIEW_COMMENT_EVENT.equals(event)) {
            return parseReviewCommentEvent(payload);
        }
        return GitHubPullRequestWebhookPayload.ignored("unsupported event", null, event);
    }

    /**
     * diff 行上的评论（{@code pull_request_review_comment}，R14）。
     *
     * <p>这是问问题最自然的位置——就在出问题的代码旁边。此前该事件直接落进
     * {@code unsupported event}，用户在 diff 上 @bot 毫无反应。</p>
     *
     * <h2>两处与 {@code issue_comment} 的结构差异</h2>
     * <ol>
     *   <li>PR 号在 {@code pull_request.number}，而不是 {@code issue.number}；
     *       锚定信息在 {@code comment.path} / {@code comment.line}。</li>
     *   <li><b>回复目标是线程根。</b>{@code in_reply_to_id} 指向被回复的那条评论，
     *       而 GitHub 的 replies 接口要求传<b>线程根</b>的 id；对一条回复再传它自己的 id 会失败。
     *       所以有 {@code in_reply_to_id} 时用它，否则用本条评论的 id（顶层评论即线程根）。</li>
     * </ol>
     */
    private GitHubPullRequestWebhookPayload parseReviewCommentEvent(String payload) {
        if (!reviewCommentMentionEnabled) {
            return GitHubPullRequestWebhookPayload.ignored(
                    "review comment mention is disabled", null, REVIEW_COMMENT_EVENT);
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            String action = text(root, "action");
            if (!"created".equals(action)) {
                return GitHubPullRequestWebhookPayload.ignored("unsupported action", action, REVIEW_COMMENT_EVENT);
            }
            JsonNode pullRequestNode = root.at("/pull_request");
            if (pullRequestNode.isMissingNode() || pullRequestNode.isNull()) {
                return GitHubPullRequestWebhookPayload.ignored("not pull request review comment", action, REVIEW_COMMENT_EVENT);
            }

            String commentBody = text(root, "comment", "body");
            if (CommentMarkers.containsAnyIdentity(
                    commentBody, CommentMarkers.DEFAULT_HEADER_LINES, summaryMarker)) {
                return GitHubPullRequestWebhookPayload.ignored("bot response", action, REVIEW_COMMENT_EVENT);
            }
            GithubCommand command = commandParser.parse(commentBody);
            if (command.shouldIgnore()) {
                return GitHubPullRequestWebhookPayload.ignored(
                        "unsupported comment command", action, REVIEW_COMMENT_EVENT);
            }

            String owner = text(root.at("/repository/owner"), "login");
            String repo = text(root, "repository", "name");
            Integer pullNumber = integer(root, "pull_request", "number");
            String prUrl = text(root, "pull_request", "html_url");
            String title = text(root, "pull_request", "title");
            String headSha = text(root.at("/pull_request/head"), "sha");
            Long commentId = longValue(root, "comment", "id");
            String commentUserLogin = text(root.at("/comment/user"), "login");
            String commentAuthorAssociation = text(root, "comment", "author_association");
            String anchorPath = text(root, "comment", "path");
            Integer anchorLine = integer(root, "comment", "line");
            if (anchorLine == null) {
                // 评论所在行被后续提交改掉后 line 会为空，original_line 仍指向当时那一行。
                anchorLine = integer(root, "comment", "original_line");
            }
            Long inReplyTo = longValue(root, "comment", "in_reply_to_id");
            Long replyToCommentId = inReplyTo != null ? inReplyTo : commentId;

            if (!StringUtils.hasText(owner) || !StringUtils.hasText(repo) || pullNumber == null) {
                throw new BusinessException("invalid GitHub pull_request_review_comment webhook payload");
            }
            if (!StringUtils.hasText(prUrl)) {
                prUrl = "https://github.com/" + owner + "/" + repo + "/pull/" + pullNumber;
            }

            return GitHubPullRequestWebhookPayload.reviewCommentCommand(
                    action,
                    owner,
                    repo,
                    pullNumber,
                    prUrl,
                    title,
                    headSha,
                    commentId,
                    commentBody,
                    commentUserLogin,
                    commentAuthorAssociation,
                    anchorPath,
                    anchorLine,
                    replyToCommentId,
                    command.getType().name(),
                    command.getText(),
                    command.isMentionedBot(),
                    command.isDryRun()
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("failed to parse GitHub webhook payload: "
                    + SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }

    private GitHubPullRequestWebhookPayload parsePullRequestEvent(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String action = text(root, "action");
            if (!SUPPORTED_ACTIONS.contains(action)) {
                return GitHubPullRequestWebhookPayload.ignored("unsupported action", action, PULL_REQUEST_EVENT);
            }

            String owner = text(root.at("/repository/owner"), "login");
            String repo = text(root, "repository", "name");
            Integer pullNumber = integer(root, "pull_request", "number");
            String prUrl = text(root, "pull_request", "html_url");
            String title = text(root, "pull_request", "title");
            String headSha = text(root.at("/pull_request/head"), "sha");

            if (!StringUtils.hasText(owner) || !StringUtils.hasText(repo) || pullNumber == null) {
                throw new BusinessException("invalid GitHub pull_request webhook payload");
            }
            if (!StringUtils.hasText(prUrl)) {
                prUrl = "https://github.com/" + owner + "/" + repo + "/pull/" + pullNumber;
            }

            return GitHubPullRequestWebhookPayload.supported(action, owner, repo, pullNumber, prUrl, title, headSha);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("failed to parse GitHub webhook payload: "
                    + SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }

    private GitHubPullRequestWebhookPayload parseIssueCommentEvent(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String action = text(root, "action");
            if (!"created".equals(action)) {
                return GitHubPullRequestWebhookPayload.ignored("unsupported action", action, ISSUE_COMMENT_EVENT);
            }

            JsonNode pullRequestNode = root.at("/issue/pull_request");
            if (pullRequestNode.isMissingNode() || pullRequestNode.isNull()) {
                return GitHubPullRequestWebhookPayload.ignored("not pull request comment", action, ISSUE_COMMENT_EVENT);
            }

            String commentBody = text(root, "comment", "body");
            // 机器人自己发的任何评论都必须在这里被挡掉，否则「收到评论 → 回一条评论 → 又收到评论」
            // 会形成自循环。因此这里判断的是**任意**已知身份标记，而不只是审查摘要标记。
            if (CommentMarkers.containsAnyIdentity(
                    commentBody, CommentMarkers.DEFAULT_HEADER_LINES, summaryMarker)) {
                return GitHubPullRequestWebhookPayload.ignored("bot response", action, ISSUE_COMMENT_EVENT);
            }
            GithubCommand command = commandParser.parse(commentBody);
            if (command.shouldIgnore()) {
                return GitHubPullRequestWebhookPayload.ignored("unsupported comment command", action, ISSUE_COMMENT_EVENT);
            }

            String owner = text(root.at("/repository/owner"), "login");
            String repo = text(root, "repository", "name");
            Integer pullNumber = integer(root, "issue", "number");
            String prUrl = text(root, "issue", "html_url");
            String title = text(root, "issue", "title");
            Long commentId = longValue(root, "comment", "id");
            String commentUserLogin = text(root.at("/comment/user"), "login");
            String commentAuthorAssociation = text(root, "comment", "author_association");

            if (!StringUtils.hasText(owner) || !StringUtils.hasText(repo) || pullNumber == null) {
                throw new BusinessException("invalid GitHub issue_comment webhook payload");
            }
            if (!StringUtils.hasText(prUrl)) {
                prUrl = "https://github.com/" + owner + "/" + repo + "/pull/" + pullNumber;
            }

            return GitHubPullRequestWebhookPayload.reviewCommand(
                    action,
                    owner,
                    repo,
                    pullNumber,
                    prUrl,
                    title,
                    commentId,
                    commentBody,
                    commentUserLogin,
                    commentAuthorAssociation,
                    command.getType().name(),
                    command.getText(),
                    command.isMentionedBot(),
                    command.isDryRun()
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("failed to parse GitHub webhook payload: "
                    + SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }

    private String text(JsonNode root, String fieldName) {
        JsonNode node = root == null ? null : root.get(fieldName);
        return node == null || node.isNull() ? null : node.asText();
    }

    private String text(JsonNode root, String parentName, String fieldName) {
        JsonNode parent = root == null ? null : root.get(parentName);
        return text(parent, fieldName);
    }

    private Integer integer(JsonNode root, String parentName, String fieldName) {
        JsonNode parent = root == null ? null : root.get(parentName);
        JsonNode node = parent == null ? null : parent.get(fieldName);
        return node == null || node.isNull() ? null : node.asInt();
    }

    private Long longValue(JsonNode root, String parentName, String fieldName) {
        JsonNode parent = root == null ? null : root.get(parentName);
        JsonNode node = parent == null ? null : parent.get(fieldName);
        return node == null || node.isNull() ? null : node.asLong();
    }
}
