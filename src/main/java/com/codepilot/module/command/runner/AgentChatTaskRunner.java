package com.codepilot.module.command.runner;

import com.codepilot.common.util.PromptInputSanitizer;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.command.chat.ReviewSessionContextBuilder;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.handler.GithubCommandChatAiAssistant;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.command.state.PrCommandTaskStateManager;
import com.codepilot.module.command.trace.AgentTaskTraceContext;
import com.codepilot.module.command.approval.FixApprovalCommandRouter;
import com.codepilot.module.command.failure.PrCommandTaskFailureHandler;
import com.codepilot.module.command.runtime.AgentRunService;
import com.codepilot.module.command.entity.AgentRun;
import com.codepilot.module.command.config.AgentRuntimeProperties;
import com.codepilot.module.command.chat.AgentConversationContextBuilder;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.review.report.ReviewReportFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class AgentChatTaskRunner {

    private static final int MAX_CHAT_COMMENT_LENGTH = 4000;

    private final GithubClient githubClient;

    private final ObjectProvider<GithubCommandChatAiAssistant> chatAiAssistantProvider;

    private final ReviewSessionContextBuilder reviewSessionContextBuilder;

    private final PrCommandTaskLogService commandTaskLogService;

    private final PrCommandTaskStateManager commandTaskStateManager;

    private final FixApprovalCommandRouter approvalCommandRouter;

    private final PrCommandTaskFailureHandler failureHandler;

    private final AgentRunService agentRunService;

    private final AgentRuntimeProperties agentRuntimeProperties;

    private final AgentConversationContextBuilder conversationContextBuilder;

    public AgentChatTaskRunner(
            GithubClient githubClient,
            ObjectProvider<GithubCommandChatAiAssistant> chatAiAssistantProvider,
            ReviewSessionContextBuilder reviewSessionContextBuilder,
            PrCommandTaskLogService commandTaskLogService,
            PrCommandTaskStateManager commandTaskStateManager
    ) {
        this(githubClient, chatAiAssistantProvider, reviewSessionContextBuilder,
                commandTaskLogService, commandTaskStateManager, null, null, null, null, null);
    }

    public void run(PrCommandTask task) {
        commandTaskStateManager.markRunning(task);
        commandTaskLogService.record(task.getId(), "AGENT_START", true,
                "Agent chat task started.", "commandType=CHAT");
        AgentRun agentRun = null;
        try {
            java.util.Optional<String> approvalResponse = approvalCommandRouter == null
                    ? java.util.Optional.empty()
                    : approvalCommandRouter.route(task);
            if (approvalResponse.isPresent()) {
                publishAndComplete(task, approvalResponse.get(), "APPROVAL_ROUTED");
                return;
            }
            agentRun = agentRunService == null ? null : agentRunService.getOrCreate(task);
            if (agentRun != null) {
                agentRunService.beginModelTurn(agentRun);
            }
            GithubCommandChatAiAssistant assistant = chatAiAssistantProvider.getIfAvailable();
            if (assistant == null) {
                throw new IllegalStateException("chat assistant bean is unavailable");
            }
            String reviewContext = reviewSessionContextBuilder.build(
                    task.getRepoOwner(), task.getRepoName(), task.getPrNumber());
            if (agentRun != null && StringUtils.hasText(agentRun.getContinuationContext())) {
                reviewContext = reviewContext + "\nworkflowContinuationStatus: AVAILABLE\n"
                        + promptSafe(agentRun.getContinuationContext());
            }
            commandTaskLogService.record(task.getId(), "CONTEXT_READY", true,
                    "Stored review context loaded.", "contextLength=" + safeLength(reviewContext));

            Long agentRunId = agentRun == null ? null : agentRun.getId();
            String finalReviewContext = reviewContext;
            String conversationContext = conversationContextBuilder == null
                    ? null : conversationContextBuilder.build(task);
            AgentTaskTraceContext.ExecutionResult<String> execution = AgentTaskTraceContext.runTracked(
                    task.getId(), agentRunId, () -> conversationContextBuilder == null
                            ? assistant.reply(
                            promptSafe(task.getCommentBody()),
                            promptSafe(task.getCommandText()),
                            promptSafe(finalReviewContext),
                            task.getRepoOwner(),
                            task.getRepoName(),
                            task.getPrNumber())
                            : assistant.replyWithConversation(
                            promptSafe(task.getCommentBody()),
                            promptSafe(task.getCommandText()),
                            promptSafe(finalReviewContext),
                            promptSafe(conversationContext),
                            task.getRepoOwner(),
                            task.getRepoName(),
                            task.getPrNumber()));
            if (execution.scheduledWorkflow() != null
                    && agentRun != null
                    && agentRuntimeProperties != null
                    && agentRuntimeProperties.isResumableEnabled()) {
                agentRunService.markWaiting(agentRun,
                        execution.scheduledWorkflow().type(), execution.scheduledWorkflow().taskId());
                commandTaskStateManager.markWaiting(task);
                commandTaskLogService.record(task.getId(), "AGENT_WAITING", true,
                        "Agent is waiting for an asynchronous workflow.",
                        "workflowType=" + execution.scheduledWorkflow().type()
                                + ", workflowTaskId=" + execution.scheduledWorkflow().taskId());
                return;
            }
            publishAndComplete(task, execution.value(), "AGENT_RESPONSE");
            if (agentRun != null) {
                agentRunService.complete(agentRun, execution.value());
            }
        } catch (Exception exception) {
            String message = SensitiveDataSanitizer.redactAndTruncate(exception.getMessage(), 1000);
            log.warn("Agent chat task failed, commandTaskId={}, errorType={}, message={}",
                    task.getId(), exception.getClass().getSimpleName(), message);
            if (failureHandler == null) {
                if (agentRun != null && agentRunService != null) {
                    agentRunService.fail(agentRun, message);
                }
                commandTaskStateManager.markFailed(task, message);
                tryPostFailureComment(task);
                return;
            }
            AgentRun failedRun = agentRun;
            failureHandler.handleAgentRetryable(task, exception, () -> {
                if (failedRun != null && agentRunService != null) {
                    agentRunService.fail(failedRun, message);
                }
                tryPostFailureComment(task);
            });
        }
    }

    private void publishAndComplete(PrCommandTask task, String response, String responseStep) {
        String safeResponse = sanitizeResponse(response);
        commandTaskStateManager.storeResponsePreview(task, safeResponse);
        commandTaskLogService.record(task.getId(), responseStep, true,
                "Agent response generated.", "responseLength=" + safeResponse.length());
        githubClient.createPullRequestComment(
                task.getRepoOwner(), task.getRepoName(), task.getPrNumber(),
                ReviewReportFormatter.DEFAULT_COMMENT_MARKER + "\n\n" + safeResponse);
        commandTaskLogService.record(task.getId(), "COMMENT_PUBLISHED", true,
                "Agent response published to GitHub.", null);
        commandTaskStateManager.markSuccess(task, null);
    }

    private String sanitizeResponse(String response) {
        String safe = SensitiveDataSanitizer.redact(response == null ? "" : response)
                .replace(ReviewReportFormatter.DEFAULT_COMMENT_MARKER, "")
                .replace("\u0000", "")
                .trim();
        if (!StringUtils.hasText(safe)) {
            return "暂时无法生成有效回复，请稍后重试。";
        }
        return safe.length() <= MAX_CHAT_COMMENT_LENGTH
                ? safe
                : SensitiveDataSanitizer.truncatePreservingRedactionMarker(safe, MAX_CHAT_COMMENT_LENGTH)
                + "\n\n... truncated ...";
    }

    private String promptSafe(String value) {
        return PromptInputSanitizer.escapeUntrustedBlockDelimiters(value == null ? "" : value);
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private void tryPostFailureComment(PrCommandTask task) {
        try {
            githubClient.createPullRequestComment(
                    task.getRepoOwner(), task.getRepoName(), task.getPrNumber(),
                    ReviewReportFormatter.DEFAULT_COMMENT_MARKER
                            + "\n\n**CodePilot AI** 暂时无法完成本次 Agent 请求，请稍后重试。");
        } catch (Exception ignored) {
            log.warn("Failed to publish agent failure comment, commandTaskId={}", task.getId());
        }
    }
}
