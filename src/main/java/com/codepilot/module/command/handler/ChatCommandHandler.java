package com.codepilot.module.command.handler;

import com.codepilot.common.util.PromptInputSanitizer;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.command.chat.ReviewSessionContextBuilder;
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

    private static final int MAX_CHAT_COMMENT_LENGTH = 4000;

    private static final String DEFAULT_CHAT_RESPONSE =
            "我可以帮你总结这个 PR、解释 review 发现，或根据最新审查结果说明证据。";

    private final GithubClient githubClient;

    private final ObjectProvider<GithubCommandChatAiAssistant> chatAiAssistantProvider;

    private final ObjectProvider<ReviewSessionContextBuilder> reviewSessionContextBuilderProvider;

    private final LlmProperties llmProperties;

    @Override
    public GithubCommandType commandType() {
        return GithubCommandType.CHAT;
    }

    @Override
    public GithubCommandHandleResult handle(GitHubPullRequestWebhookPayload payload) {
        if (!hasReviewTarget(payload)) {
            String action = safeAction(payload);
            log.warn("Skip GitHub command chat because PR identity is incomplete, action={}, owner={}, repo={}, pullNumber={}",
                    action,
                    payload == null ? null : payload.getOwner(),
                    payload == null ? null : payload.getRepo(),
                    payload == null ? null : payload.getPullNumber());
            return GithubCommandHandleResult.ignored("missing PR identity", action);
        }
        try {
            String unavailableReason = llmUnavailableReason();
            if (unavailableReason != null) {
                tryPostUnavailableComment(payload, unavailableReason);
                return GithubCommandHandleResult.processed(null, payload.getAction());
            }

            GithubCommandChatAiAssistant assistant = chatAiAssistantProvider == null ? null : chatAiAssistantProvider.getIfAvailable();
            if (assistant == null) {
                tryPostUnavailableComment(payload, "chat assistant bean is unavailable");
                return GithubCommandHandleResult.processed(null, payload.getAction());
            }
            String reviewSessionContext = normalizeReviewSessionContext(buildReviewSessionContext(payload));

            String response = assistant.reply(
                    promptSafeUserInput(payload.getCommentBody()),
                    promptSafeUserInput(payload.getCommandText()),
                    promptSafeServerContext(reviewSessionContext),
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
                    safeOwner(payload), safeRepo(payload), safePullNumber(payload),
                    exception.getClass().getSimpleName(), safeLogText(exception.getMessage()));
            tryPostUnavailableComment(payload, "chat command failed: " + exception.getClass().getSimpleName());
        }
        return GithubCommandHandleResult.processed(null, payload.getAction());
    }

    private String llmUnavailableReason() {
        if (llmProperties == null) {
            return "llm properties are not configured";
        }
        if (!llmProperties.isEnabled()) {
            return "llm is disabled";
        }
        if (!StringUtils.hasText(llmProperties.getApiKey())) {
            return "llm api key is missing";
        }
        if (!StringUtils.hasText(llmProperties.getBaseUrl())) {
            return "llm base url is missing";
        }
        if (!StringUtils.hasText(llmProperties.getModel())) {
            return "llm model is missing";
        }
        return null;
    }

    private boolean postUnavailableComment(GitHubPullRequestWebhookPayload payload, String reason) {
        if (!hasReviewTarget(payload)) {
            log.warn("Skip GitHub command chat unavailable comment because PR identity is incomplete, action={}, owner={}, repo={}, pullNumber={}, reason={}",
                    safeAction(payload), safeOwner(payload), safeRepo(payload), safePullNumber(payload), safeLogText(reason));
            return false;
        }
        log.info("Post GitHub command chat unavailable comment, owner={}, repo={}, pullNumber={}, reason={}",
                safeOwner(payload), safeRepo(payload), safePullNumber(payload), safeLogText(reason));
        githubClient.createPullRequestComment(
                safeOwner(payload),
                safeRepo(payload),
                safePullNumber(payload),
                """
                %s

                **CodePilot AI** 当前暂时不可用。

                当前命令需要 LLM 服务，但模型尚未配置或调用失败。请检查 `CODEPILOT_LLM_API_KEY`、`CODEPILOT_LLM_BASE_URL` 和 `CODEPILOT_LLM_MODEL`，然后再试一次。
                """.formatted(ReviewReportFormatter.DEFAULT_COMMENT_MARKER)
        );
        return true;
    }

    private boolean tryPostUnavailableComment(GitHubPullRequestWebhookPayload payload, String reason) {
        try {
            return postUnavailableComment(payload, reason);
        } catch (Exception exception) {
            log.warn("GitHub command unavailable comment failed but ignored, owner={}, repo={}, pullNumber={}, reason={}, errorType={}, message={}",
                    safeOwner(payload), safeRepo(payload), safePullNumber(payload), safeLogText(reason),
                    exception.getClass().getSimpleName(), safeLogText(exception.getMessage()));
            return false;
        }
    }

    private String formatCommentBody(String response) {
        String content = StringUtils.hasText(response)
                ? sanitizeAssistantResponse(response)
                : DEFAULT_CHAT_RESPONSE;
        return ReviewReportFormatter.DEFAULT_COMMENT_MARKER + "\n\n" + content;
    }

    private String sanitizeAssistantResponse(String response) {
        String safe = SensitiveDataSanitizer.redact(response)
                .replace(ReviewReportFormatter.DEFAULT_COMMENT_MARKER, "")
                .replace("\u0000", "")
                .trim();
        if (!StringUtils.hasText(safe)) {
            return DEFAULT_CHAT_RESPONSE;
        }
        if (safe.length() <= MAX_CHAT_COMMENT_LENGTH) {
            return safe;
        }
        return SensitiveDataSanitizer.truncatePreservingRedactionMarker(safe, MAX_CHAT_COMMENT_LENGTH)
                + "\n\n... truncated ...";
    }

    private String buildReviewSessionContext(GitHubPullRequestWebhookPayload payload) {
        if (!hasReviewTarget(payload)) {
            return unavailableReviewSessionContext("Stored review context is unavailable because the PR identity is incomplete.");
        }
        ReviewSessionContextBuilder contextBuilder = reviewSessionContextBuilderProvider == null
                ? null
                : reviewSessionContextBuilderProvider.getIfAvailable();
        if (contextBuilder == null) {
            return unavailableReviewSessionContext("Stored review context is unavailable because the review session context builder is not configured.");
        }
        try {
            return contextBuilder.build(payload.getOwner(), payload.getRepo(), payload.getPullNumber());
        } catch (Exception exception) {
            log.warn("Failed to build GitHub command review session context, owner={}, repo={}, pullNumber={}, errorType={}, message={}",
                    safeOwner(payload), safeRepo(payload), safePullNumber(payload),
                    exception.getClass().getSimpleName(), safeLogText(exception.getMessage()));
            return unavailableReviewSessionContext("Stored review context is unavailable because the server failed to load the latest review session.");
        }
    }

    private String safeText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String promptSafeUserInput(String value) {
        String redacted = SensitiveDataSanitizer.redact(safeText(value, ""));
        return PromptInputSanitizer.escapeUntrustedBlockDelimiters(redacted);
    }

    private String promptSafeServerContext(String value) {
        return PromptInputSanitizer.escapeUntrustedBlockDelimiters(safeText(value, ""));
    }

    private String normalizeReviewSessionContext(String value) {
        return StringUtils.hasText(value)
                ? value
                : unavailableReviewSessionContext("Stored review context is unavailable because the server returned an empty review session.");
    }

    private String unavailableReviewSessionContext(String reason) {
        String safeReason = PromptInputSanitizer.escapeUntrustedBlockDelimiters(safeLogText(reason))
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return """
                reviewSessionContextStatus: UNAVAILABLE
                hasSuccessfulReview: false
                reason: %s
                """.formatted(StringUtils.hasText(safeReason) ? safeReason : "Stored review context is unavailable.");
    }

    private boolean hasReviewTarget(GitHubPullRequestWebhookPayload payload) {
        return payload != null
                && StringUtils.hasText(payload.getOwner())
                && StringUtils.hasText(payload.getRepo())
                && payload.getPullNumber() != null;
    }

    private String safeOwner(GitHubPullRequestWebhookPayload payload) {
        return payload == null ? null : payload.getOwner();
    }

    private String safeRepo(GitHubPullRequestWebhookPayload payload) {
        return payload == null ? null : payload.getRepo();
    }

    private Integer safePullNumber(GitHubPullRequestWebhookPayload payload) {
        return payload == null ? null : payload.getPullNumber();
    }

    private String safeAction(GitHubPullRequestWebhookPayload payload) {
        return StringUtils.hasText(payload == null ? null : payload.getAction()) ? payload.getAction() : "unknown";
    }

    private String safeLogText(String value) {
        return SensitiveDataSanitizer.redactAndTruncate(safeText(value, ""), 300);
    }
}
