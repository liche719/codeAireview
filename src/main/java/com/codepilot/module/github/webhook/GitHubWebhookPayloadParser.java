package com.codepilot.module.github.webhook;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.command.dto.GithubCommand;
import com.codepilot.module.command.parser.GithubCommandParser;
import com.codepilot.module.review.report.CommentMarkers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

@Component
public class GitHubWebhookPayloadParser {

    private static final String PULL_REQUEST_EVENT = "pull_request";

    private static final String ISSUE_COMMENT_EVENT = "issue_comment";

    private static final Set<String> SUPPORTED_ACTIONS = Set.of("opened", "synchronize", "reopened");

    private final ObjectMapper objectMapper;

    private final GithubCommandParser commandParser;

    /**
     * 审查摘要的身份标记（可配置）。用于识别"这条评论是机器人自己发的"。
     * 单元测试手工构造本类时为 null，{@link CommentMarkers#containsAnyIdentity} 会安全跳过。
     */
    @Value("${codepilot.github.comment-marker:}")
    private String summaryMarker;

    public GitHubWebhookPayloadParser(ObjectMapper objectMapper, GithubCommandParser commandParser) {
        this.objectMapper = objectMapper;
        this.commandParser = commandParser;
    }

    public GitHubPullRequestWebhookPayload parse(String event, String payload) {
        if (PULL_REQUEST_EVENT.equals(event)) {
            return parsePullRequestEvent(payload);
        }
        if (ISSUE_COMMENT_EVENT.equals(event)) {
            return parseIssueCommentEvent(payload);
        }
        return GitHubPullRequestWebhookPayload.ignored("unsupported event", null, event);
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
