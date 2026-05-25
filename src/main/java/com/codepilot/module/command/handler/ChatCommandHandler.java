package com.codepilot.module.command.handler;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.common.util.PromptInputSanitizer;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.dto.GithubCommandType;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubLinkedIssue;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import com.codepilot.module.review.report.ReviewReportFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatCommandHandler implements GithubCommandHandler {

    private static final int MAX_CHAT_COMMENT_LENGTH = 4000;

    private final GithubClient githubClient;

    private final ObjectProvider<GithubCommandChatAiAssistant> chatAiAssistantProvider;

    private final LlmProperties llmProperties;

    @Override
    public GithubCommandType commandType() {
        return GithubCommandType.CHAT;
    }

    @Override
    public GithubCommandHandleResult handle(GitHubPullRequestWebhookPayload payload) {
        try {
            if (!isLlmAvailable()) {
                postUnavailableComment(payload);
                return GithubCommandHandleResult.processed(null, payload.getAction());
            }

            GithubCommandChatAiAssistant assistant = chatAiAssistantProvider == null ? null : chatAiAssistantProvider.getIfAvailable();
            if (assistant == null) {
                postUnavailableComment(payload);
                return GithubCommandHandleResult.processed(null, payload.getAction());
            }

            String linkedIssuesContext = promptSafe(buildLinkedIssuesContext(payload));
            String response = assistant.reply(
                    promptSafe(payload.getCommentBody()),
                    promptSafe(payload.getCommandText()),
                    linkedIssuesContext,
                    payload.getOwner(),
                    payload.getRepo(),
                    payload.getPullNumber()
            );
            githubClient.createPullRequestComment(
                    payload.getOwner(),
                    payload.getRepo(),
                    payload.getPullNumber(),
                    formatCommentBody(response)
            );
        } catch (Exception exception) {
            log.warn("GitHub command chat comment failed but ignored, owner={}, repo={}, pullNumber={}, errorType={}, message={}",
                    payload.getOwner(), payload.getRepo(), payload.getPullNumber(),
                    exception.getClass().getSimpleName(), SensitiveDataSanitizer.redact(exception.getMessage()));
            try {
                postUnavailableComment(payload);
            } catch (Exception ignored) {
                log.warn("GitHub command fallback unavailable comment failed but ignored, owner={}, repo={}, pullNumber={}",
                        payload.getOwner(), payload.getRepo(), payload.getPullNumber());
            }
        }
        return GithubCommandHandleResult.processed(null, payload.getAction());
    }

    private boolean isLlmAvailable() {
        return llmProperties != null
                && llmProperties.isEnabled()
                && StringUtils.hasText(llmProperties.getApiKey())
                && StringUtils.hasText(llmProperties.getBaseUrl())
                && StringUtils.hasText(llmProperties.getModel());
    }

    private void postUnavailableComment(GitHubPullRequestWebhookPayload payload) {
                githubClient.createPullRequestComment(
                payload.getOwner(),
                payload.getRepo(),
                payload.getPullNumber(),
                """
                %s

                **CodePilot AI** 当前暂时不可用。

                当前命令需要 LLM 服务，但模型尚未配置或调用失败。请检查 `CODEPILOT_LLM_API_KEY`、`CODEPILOT_LLM_BASE_URL` 和 `CODEPILOT_LLM_MODEL`，然后再试一次。
                """.formatted(ReviewReportFormatter.DEFAULT_COMMENT_MARKER)
        );
    }

    private String formatCommentBody(String response) {
        String content = StringUtils.hasText(response)
                ? sanitizeAssistantResponse(response)
                : "我可以帮你审查这个 PR、修复一个具体问题，或者总结一下变更。";
        return ReviewReportFormatter.DEFAULT_COMMENT_MARKER + "\n\n" + content;
    }

    private String sanitizeAssistantResponse(String response) {
        String safe = SensitiveDataSanitizer.redact(response)
                .replace(ReviewReportFormatter.DEFAULT_COMMENT_MARKER, "")
                .replace("\u0000", "")
                .trim();
        if (!StringUtils.hasText(safe)) {
            return "我可以帮你审查这个 PR、修复一个具体问题，或者总结一下变更。";
        }
        if (safe.length() <= MAX_CHAT_COMMENT_LENGTH) {
            return safe;
        }
        return SensitiveDataSanitizer.truncatePreservingRedactionMarker(safe, MAX_CHAT_COMMENT_LENGTH)
                + "\n\n... truncated ...";
    }

    private String safeText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String promptSafe(String value) {
        return PromptInputSanitizer.escapeUntrustedBlockDelimiters(safeText(value, ""));
    }

    private String buildLinkedIssuesContext(GitHubPullRequestWebhookPayload payload) {
        if (payload == null) {
            return "";
        }
        try {
            List<GithubLinkedIssue> issues = githubClient.listPullRequestLinkedIssues(
                    payload.getOwner(),
                    payload.getRepo(),
                    payload.getPullNumber()
            );
            if (issues == null || issues.isEmpty()) {
                return "Linked issues: none found.";
            }

            StringBuilder context = new StringBuilder();
            appendLine(context, "Linked issues:");
            for (GithubLinkedIssue issue : issues) {
                appendLinkedIssue(context, issue);
            }
            return context.toString();
        } catch (Exception exception) {
            log.warn("GitHub linked issue context lookup failed, owner={}, repo={}, pullNumber={}, message={}",
                    payload.getOwner(), payload.getRepo(), payload.getPullNumber(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
            return "Linked issues lookup failed.";
        }
    }

    private void appendLine(StringBuilder builder, String line) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(line);
    }

    private void appendLinkedIssue(StringBuilder context, GithubLinkedIssue issue) {
        if (issue == null) {
            return;
        }
        appendLine(context, "- #" + defaultNumber(issue.getNumber())
                + " " + safeText(issue.getTitle(), "N/A")
                + " (" + safeText(issue.getState(), "N/A") + ")"
                + " - " + safeText(issue.getHtmlUrl(), buildFallbackIssueUrl(
                safeText(issue.getRepositoryOwner(), "unknown"),
                safeText(issue.getRepositoryName(), "unknown"),
                issue.getNumber()
        )));
        if (StringUtils.hasText(issue.getBodySummary())) {
            appendLine(context, "  summary: " + issue.getBodySummary());
        }
        appendLine(context, "  source: " + safeText(issue.getLinkSource(), "N/A"));
    }

    private int defaultNumber(Integer value) {
        return value == null ? 0 : value;
    }

    private String buildFallbackIssueUrl(String owner, String repo, Integer issueNumber) {
        return "https://github.com/" + safeText(owner, "unknown") + "/" + safeText(repo, "unknown") + "/issues/" + issueNumber;
    }
}
