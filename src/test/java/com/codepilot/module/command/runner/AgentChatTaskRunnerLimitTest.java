package com.codepilot.module.command.runner;

import com.codepilot.common.retry.RabbitRetryAttemptResolver;
import com.codepilot.module.command.chat.ReviewSessionContextBuilder;
import com.codepilot.module.command.config.AgentRuntimeProperties;
import com.codepilot.module.command.entity.AgentRun;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.failure.PrCommandTaskFailureHandler;
import com.codepilot.module.command.fix.FixResultCommenter;
import com.codepilot.module.command.handler.GithubCommandChatAiAssistant;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.command.runtime.AgentLimitExceededException;
import com.codepilot.module.command.runtime.AgentRunService;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.command.state.PrCommandTaskStateManager;
import com.codepilot.module.git.client.GithubClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R8：撞到硬上限时的<b>终态</b>行为。
 *
 * <p>整个类围绕一条最容易写错的性质：硬上限失败必须<b>不再重试</b>。
 * 判定方式是"{@code run} 正常返回"——可重试路径 {@code handleAgentRetryable}
 * 末尾会 throw 出去触发 MQ 重投，所以"没有异常逃出"就等价于"没有走重试"。
 * 相比断言 mock 的调用次数，这个断言直接绑在可观测行为上，更难被误改。</p>
 */
class AgentChatTaskRunnerLimitTest {

    /** 硬上限 → 终态：不抛异常（抛了就会被 MQ 重投，而重试还会撞同一堵墙）。 */
    @Test
    void shouldEndTaskWithoutRetryWhenAHardLimitIsHit() {
        Fixture fixture = new Fixture();
        fixture.assistantThrows(new AgentLimitExceededException(
                AgentLimitExceededException.LimitType.TOOL_CALLS, 13, 12));

        assertThatCode(() -> fixture.runner.run(fixture.task)).doesNotThrowAnyException();

        assertThat(fixture.task.getStatus()).isEqualTo("FAILED");
    }

    /** 用户必须收到"撞了哪个上限 + 怎么办"，而不是通用的"请稍后重试"。 */
    @Test
    void shouldTellTheUserWhichLimitWasHit() {
        Fixture fixture = new Fixture();
        fixture.assistantThrows(new AgentLimitExceededException(
                AgentLimitExceededException.LimitType.TOOL_CALLS, 13, 12));
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        fixture.runner.run(fixture.task);

        verify(fixture.githubClient).createPullRequestComment(
                eq("liche719"), eq("codeAireview"), eq(12), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .contains("工具调用")
                .contains("12 次")
                .doesNotContain("请稍后重试");
    }

    /** 终态失败要落到任务与 agent_run 上，否则运维看不到是哪一类上限在持续发生。 */
    @Test
    void shouldMarkTaskAndAgentRunAsFailed() {
        Fixture fixture = new Fixture();
        fixture.assistantThrows(new AgentLimitExceededException(
                AgentLimitExceededException.LimitType.RUN_DEADLINE, 1800, 1800));

        fixture.runner.run(fixture.task);

        assertThat(fixture.task.getStatus()).isEqualTo("FAILED");
        verify(fixture.agentRunService).fail(eq(fixture.agentRun), anyString());
    }

    /** 四种上限都要走同一条终态路径，不能漏掉任何一种。 */
    @Test
    void shouldHandleEveryLimitTypeTerminally() {
        for (AgentLimitExceededException.LimitType type : AgentLimitExceededException.LimitType.values()) {
            Fixture fixture = new Fixture();
            fixture.assistantThrows(new AgentLimitExceededException(type, 99, 10));

            assertThatCode(() -> fixture.runner.run(fixture.task))
                    .as("limit type %s must not propagate (propagating means a retry)", type)
                    .doesNotThrowAnyException();
            assertThat(fixture.task.getStatus()).as("limit type %s", type).isEqualTo("FAILED");
        }
    }

    /**
     * 被框架包装过的上限异常也必须被识别为终态。
     *
     * <p>这是真实路径：工具调用上限在 {@code @Tool} 方法里抛出，要穿过 LangChain4j 的
     * 调用栈才能回到 runner，中间可能被包装成别的异常。只匹配顶层类型会漏掉它，
     * 而工具调用上限正是默认配置下最常撞到的那一个。</p>
     */
    @Test
    void shouldStillDetectALimitWrappedByTheToolCallStack() {
        Fixture fixture = new Fixture();
        fixture.assistantThrows(new RuntimeException("tool execution failed",
                new IllegalStateException("wrapped again",
                        new AgentLimitExceededException(
                                AgentLimitExceededException.LimitType.TOOL_CALLS, 13, 12))));
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        assertThatCode(() -> fixture.runner.run(fixture.task)).doesNotThrowAnyException();

        assertThat(fixture.task.getStatus()).isEqualTo("FAILED");
        verify(fixture.githubClient).createPullRequestComment(
                eq("liche719"), eq("codeAireview"), eq(12), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("工具调用").contains("12 次");
    }

    /** 委派断言：硬上限必须走 non-retryable 那个方法，绝不能走 retryable。 */
    @Test
    void shouldDelegateToTheNonRetryableHandler() {
        GithubClient githubClient = mock(GithubClient.class);
        PrCommandTaskFailureHandler failureHandler = mock(PrCommandTaskFailureHandler.class);
        PrCommandTask task = task();
        AgentChatTaskRunner runner = runnerWith(failureHandler, githubClient, task, new AgentLimitExceededException(
                AgentLimitExceededException.LimitType.MODEL_TURNS, 9, 8));

        runner.run(task);

        verify(failureHandler).handleAgentNonRetryable(eq(task), any(), any());
        verify(failureHandler, never()).handleAgentRetryable(any(), any(), any());
    }

    /**
     * 反向保护：真正的可重试故障仍必须走可重试路径。
     *
     * <p>如果为了 R8 把所有 Agent 失败都改成终态，那"LLM 抖一下"就变成永久失败了。</p>
     */
    @Test
    void shouldKeepOrdinaryFailuresRetryable() {
        GithubClient githubClient = mock(GithubClient.class);
        PrCommandTaskFailureHandler failureHandler = mock(PrCommandTaskFailureHandler.class);
        PrCommandTask task = task();
        AgentChatTaskRunner runner = runnerWith(
                failureHandler, githubClient, task, new IllegalStateException("llm timeout"));

        runner.run(task);

        verify(failureHandler).handleAgentRetryable(eq(task), any(), any());
        verify(failureHandler, never()).handleAgentNonRetryable(any(), any(), any());
    }

    private static AgentChatTaskRunner runnerWith(
            PrCommandTaskFailureHandler failureHandler,
            GithubClient githubClient,
            PrCommandTask task,
            RuntimeException thrown
    ) {
        GithubCommandChatAiAssistant assistant = mock(GithubCommandChatAiAssistant.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<GithubCommandChatAiAssistant> assistantProvider = mock(ObjectProvider.class);
        ReviewSessionContextBuilder contextBuilder = mock(ReviewSessionContextBuilder.class);
        when(assistantProvider.getIfAvailable()).thenReturn(assistant);
        when(contextBuilder.build(anyString(), anyString(), anyInt())).thenReturn("context");
        when(assistant.reply(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(thrown);
        return new AgentChatTaskRunner(
                githubClient,
                assistantProvider,
                contextBuilder,
                mock(PrCommandTaskLogService.class),
                new PrCommandTaskStateManager(mock(PrCommandTaskMapper.class)),
                null,
                failureHandler,
                mock(AgentRunService.class),
                new AgentRuntimeProperties(),
                null
        );
    }

    /**
     * 用<b>真实</b>的 {@code PrCommandTaskFailureHandler}，这样任务状态与评论都是真的产生的，
     * 而不是"mock 被调用了"。
     */
    private static final class Fixture {

        private final GithubClient githubClient = mock(GithubClient.class);

        private final GithubCommandChatAiAssistant assistant = mock(GithubCommandChatAiAssistant.class);

        @SuppressWarnings("unchecked")
        private final ObjectProvider<GithubCommandChatAiAssistant> assistantProvider = mock(ObjectProvider.class);

        private final ReviewSessionContextBuilder contextBuilder = mock(ReviewSessionContextBuilder.class);

        private final PrCommandTaskLogService logService = mock(PrCommandTaskLogService.class);

        private final PrCommandTaskMapper mapper = mock(PrCommandTaskMapper.class);

        private final PrCommandTaskStateManager stateManager = new PrCommandTaskStateManager(mapper);

        private final AgentRunService agentRunService = mock(AgentRunService.class);

        private final AgentRun agentRun = new AgentRun();

        private final PrCommandTask task = task();

        private final AgentChatTaskRunner runner;

        private Fixture() {
            agentRun.setId(77L);
            agentRun.setStatus("RUNNING");
            when(assistantProvider.getIfAvailable()).thenReturn(assistant);
            when(contextBuilder.build("liche719", "codeAireview", 12))
                    .thenReturn("reviewSessionContextStatus: AVAILABLE");
            when(agentRunService.getOrCreate(any())).thenReturn(agentRun);
            PrCommandTaskFailureHandler realFailureHandler = new PrCommandTaskFailureHandler(
                    stateManager,
                    logService,
                    mock(FixResultCommenter.class),
                    mock(RabbitRetryAttemptResolver.class)
            );
            runner = new AgentChatTaskRunner(
                    githubClient,
                    assistantProvider,
                    contextBuilder,
                    logService,
                    stateManager,
                    null,
                    realFailureHandler,
                    agentRunService,
                    new AgentRuntimeProperties(),
                    // null 是刻意的：让 runner 走 reply(...) 这条分支。
                    // 传入 mock 会让它改走 replyWithConversation(...)，那是另一个签名。
                    null
            );
        }

        private void assistantThrows(RuntimeException exception) {
            when(assistant.reply(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                    .thenThrow(exception);
        }
    }

    private static PrCommandTask task() {
        PrCommandTask task = new PrCommandTask();
        task.setId(9L);
        task.setCommandType("CHAT");
        task.setStatus("PENDING");
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(12);
        task.setCommentBody("@x-pilotx explain this finding");
        task.setCommandText("explain this finding");
        return task;
    }
}
