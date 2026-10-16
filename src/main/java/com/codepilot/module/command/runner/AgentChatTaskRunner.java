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
import com.codepilot.module.command.runtime.AgentLimitExceededException;
import com.codepilot.module.command.runtime.AgentRunService;
import com.codepilot.module.command.entity.AgentRun;
import com.codepilot.module.command.config.AgentRuntimeProperties;
import com.codepilot.module.command.chat.AgentConversationContextBuilder;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.review.report.CommentMarkers;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class AgentChatTaskRunner {

    private static final int MAX_CHAT_COMMENT_LENGTH = 4000;

    /** 查找硬上限异常时沿 cause 链下钻的最大深度。 */
    private static final int MAX_CAUSE_DEPTH = 8;

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

    /**
     * 审查摘要的身份标记（可配置）。这里只用于防御性剥离——模型输出里不应出现任何身份标记。
     * 单元测试手工构造本类时该字段为 null，{@link CommentMarkers#stripIdentities} 会安全跳过。
     */
    @Value("${codepilot.github.comment-marker:}")
    private String summaryMarker;

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
            // 硬上限：终态，不重试。重试只会撞同一堵墙，还会把用户拿到真实反馈的时间往后拖。
            // 这里刻意不 throw —— 抛出会让 MQ 重新投递这条消息。
            AgentLimitExceededException limit = findLimitExceeded(exception);
            if (limit != null) {
                handleLimitExceeded(task, agentRun, limit);
                return;
            }
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

    /**
     * 沿 cause 链找硬上限异常，找不到返回 {@code null}。
     *
     * <h2>为什么不只判断顶层类型</h2>
     * 工具调用上限是在 {@code @Tool} 方法内部由 {@code AgentToolTraceAspect} 抛出的，
     * 这个异常要穿过 LangChain4j 的工具调用栈才能回到这里，**中间可能被框架包装**。
     * 只匹配顶层类型会漏掉这条路径——而它恰恰是默认限额（12 次）下最常见的一种。
     *
     * <p>深度上限与自引用检查是防御性的：异常链理论上可能成环或极深，
     * 那会把"给用户一条可行动的回复"变成栈溢出。</p>
     */
    private AgentLimitExceededException findLimitExceeded(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof AgentLimitExceededException limit) {
                return limit;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                return null;
            }
            current = cause;
        }
        return null;
    }

    /**
     * 硬上限触顶的收尾：把真实原因告诉用户，并把任务置终态。
     *
     * <p>与通用失败路径的区别是回复内容——通用路径只会说"请稍后重试"，
     * 而这里必须说清<b>撞了哪个上限、上限多少、接下来怎么办</b>，
     * 否则用户唯一能做的就是原样再问一次，然后再次撞墙。</p>
     */
    private void handleLimitExceeded(PrCommandTask task, AgentRun agentRun, AgentLimitExceededException exception) {
        String limitDetail = "limitType=" + exception.getLimitType().name()
                + ", currentValue=" + exception.getCurrentValue()
                + ", limitValue=" + exception.getLimitValue();
        log.warn("Agent chat task hit a hard limit, commandTaskId={}, {}, limitType={}",
                task.getId(), limitDetail, exception.getLimitType().name());

        AgentRun failedRun = agentRun;
        Runnable terminalNotifier = () -> {
            if (failedRun != null && agentRunService != null) {
                agentRunService.fail(failedRun, exception.getMessage());
            }
            postComment(task, exception.userFacingMessage());
        };

        if (failureHandler == null) {
            terminalNotifier.run();
            return;
        }
        failureHandler.handleAgentNonRetryable(task, exception, terminalNotifier);
    }

    private void publishAndComplete(PrCommandTask task, String response, String responseStep) {
        String safeResponse = sanitizeResponse(response);
        commandTaskStateManager.storeResponsePreview(task, safeResponse);
        commandTaskLogService.record(task.getId(), responseStep, true,
                "Agent response generated.", "responseLength=" + safeResponse.length());
        githubClient.createPullRequestComment(
                task.getRepoOwner(), task.getRepoName(), task.getPrNumber(),
                CommentMarkers.wrap(CommentMarkers.AGENT_REPLY, safeResponse));
        commandTaskLogService.record(task.getId(), "COMMENT_PUBLISHED", true,
                "Agent response published to GitHub.", null);
        commandTaskStateManager.markSuccess(task, null);
    }

    private String sanitizeResponse(String response) {
        // 剥离所有身份标记（含可配置的审查摘要标记）：模型不应有能力伪造我们的评论身份，
        // 否则它可以把一条 Agent 回复伪装成审查摘要，进而在摘要更新时被命中覆盖。
        String safe = CommentMarkers.stripIdentities(
                        SensitiveDataSanitizer.redact(response == null ? "" : response), summaryMarker)
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
        postComment(task, "**CodePilot AI** 暂时无法完成本次 Agent 请求，请稍后重试。");
    }

    /**
     * 以 Agent 回复的身份发一条评论。
     *
     * <p>失败只记 warning、不向上抛：评论发不出去不该把任务状态改坏，
     * 也不该触发重试——重试会重跑整段 Agent 逻辑。</p>
     */
    private void postComment(PrCommandTask task, String text) {
        try {
            githubClient.createPullRequestComment(
                    task.getRepoOwner(), task.getRepoName(), task.getPrNumber(),
                    CommentMarkers.wrap(CommentMarkers.AGENT_REPLY, text));
        } catch (Exception ignored) {
            log.warn("Failed to publish agent comment, commandTaskId={}", task.getId());
        }
    }
}
