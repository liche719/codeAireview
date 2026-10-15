package com.codepilot.module.command.runner;

import com.codepilot.module.command.chat.ReviewSessionContextBuilder;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.handler.GithubCommandChatAiAssistant;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.command.state.PrCommandTaskStateManager;
import com.codepilot.module.git.client.GithubClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentChatTaskRunnerTest {

    @Test
    void shouldRunAgentTaskAndPersistTraceAndResponse() {
        GithubClient githubClient = mock(GithubClient.class);
        GithubCommandChatAiAssistant assistant = mock(GithubCommandChatAiAssistant.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<GithubCommandChatAiAssistant> assistantProvider = mock(ObjectProvider.class);
        ReviewSessionContextBuilder contextBuilder = mock(ReviewSessionContextBuilder.class);
        PrCommandTaskLogService logService = mock(PrCommandTaskLogService.class);
        PrCommandTaskMapper mapper = mock(PrCommandTaskMapper.class);
        when(assistantProvider.getIfAvailable()).thenReturn(assistant);
        when(contextBuilder.build("liche719", "codeAireview", 12))
                .thenReturn("reviewSessionContextStatus: AVAILABLE");
        when(assistant.reply(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn("根据审查证据，这里存在 SQL 拼接风险。");
        AgentChatTaskRunner runner = new AgentChatTaskRunner(
                githubClient,
                assistantProvider,
                contextBuilder,
                logService,
                new PrCommandTaskStateManager(mapper));
        PrCommandTask task = task();

        runner.run(task);

        assertThat(task.getStatus()).isEqualTo("SUCCESS");
        assertThat(task.getResponseBody()).contains("SQL 拼接风险");
        verify(logService).record(eq(9L), eq("AGENT_START"), eq(true), anyString(), any());
        verify(logService).record(eq(9L), eq("AGENT_RESPONSE"), eq(true), anyString(), any());
        verify(logService).record(eq(9L), eq("COMMENT_PUBLISHED"), eq(true), anyString(), any());
        verify(githubClient).createPullRequestComment(
                eq("liche719"), eq("codeAireview"), eq(12),
                org.mockito.ArgumentMatchers.contains("SQL 拼接风险"));
        verify(mapper, atLeastOnce()).updateById(task);
    }

    private PrCommandTask task() {
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
