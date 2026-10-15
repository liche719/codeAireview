package com.codepilot.module.command.handler;

import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.service.PrCommandTaskService;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatCommandHandlerTest {

    @Test
    void shouldCreateAgentTaskFromNaturalLanguageComment() {
        PrCommandTaskService taskService = mock(PrCommandTaskService.class);
        when(taskService.createChatTask(any())).thenReturn(task(42L, "PENDING"));
        ChatCommandHandler handler = new ChatCommandHandler(taskService);

        GithubCommandHandleResult result = handler.handle(payload());

        assertThat(result.getId()).isEqualTo(42L);
        verify(taskService).createChatTask(any());
    }

    @Test
    void shouldReturnExistingTaskIdWithoutRepublishing() {
        PrCommandTaskService taskService = mock(PrCommandTaskService.class);
        when(taskService.createChatTask(any())).thenReturn(task(42L, "SUCCESS"));
        ChatCommandHandler handler = new ChatCommandHandler(taskService);

        GithubCommandHandleResult result = handler.handle(payload());

        // 任务复用与投递幂等由 PrCommandTaskService 和 command outbox 负责，
        // handler 本身不持有 producer，因此这里只断言它如实返回被复用的任务。
        assertThat(result.getId()).isEqualTo(42L);
        verify(taskService).createChatTask(any());
    }

    @Test
    void shouldIgnoreRequestWhenPrIdentityIsIncomplete() {
        PrCommandTaskService taskService = mock(PrCommandTaskService.class);
        ChatCommandHandler handler = new ChatCommandHandler(taskService);
        GitHubPullRequestWebhookPayload payload = payload();
        payload.setOwner("");

        GithubCommandHandleResult result = handler.handle(payload);

        assertThat(result.getReason()).isEqualTo("missing PR identity");
        verifyNoInteractions(taskService);
    }

    private PrCommandTask task(Long id, String status) {
        PrCommandTask task = new PrCommandTask();
        task.setId(id);
        task.setStatus(status);
        return task;
    }

    private GitHubPullRequestWebhookPayload payload() {
        GitHubPullRequestWebhookPayload payload = new GitHubPullRequestWebhookPayload();
        payload.setOwner("liche719");
        payload.setRepo("codeAireview");
        payload.setPullNumber(12);
        payload.setPrUrl("https://github.com/liche719/codeAireview/pull/12");
        payload.setTitle("Add webhook support");
        payload.setCommentBody("@x-pilotx inspect Redis and prepare a fix preview");
        payload.setCommandText("inspect Redis and prepare a fix preview");
        payload.setAction("created");
        return payload;
    }
}
