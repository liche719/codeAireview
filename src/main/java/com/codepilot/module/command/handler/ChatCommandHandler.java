package com.codepilot.module.command.handler;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.dto.GithubCommandType;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import com.codepilot.module.review.report.ReviewReportFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatCommandHandler implements GithubCommandHandler {

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

            String response = assistant.reply(
                    safeText(payload.getCommentBody(), ""),
                    safeText(payload.getCommandText(), ""),
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
            log.warn("GitHub command chat comment failed but ignored, owner={}, repo={}, pullNumber={}, message={}",
                    payload.getOwner(), payload.getRepo(), payload.getPullNumber(), exception.getMessage());
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

                **CodePilot AI** is temporarily unavailable.

                The current command needs the LLM service, but the model is not configured or the LLM call failed. Please check `CODEPILOT_LLM_API_KEY`, `CODEPILOT_LLM_BASE_URL`, and `CODEPILOT_LLM_MODEL`, then try again.
                """.formatted(ReviewReportFormatter.DEFAULT_COMMENT_MARKER)
        );
    }

    private String formatCommentBody(String response) {
        String content = StringUtils.hasText(response)
                ? response.trim()
                : "I can review this PR, fix a specific issue, or summarize what changed.";
        if (content.contains(ReviewReportFormatter.DEFAULT_COMMENT_MARKER)) {
            return content;
        }
        return ReviewReportFormatter.DEFAULT_COMMENT_MARKER + "\n\n" + content;
    }

    private String safeText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
