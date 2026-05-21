package com.codepilot.module.command.handler;

import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.service.PrCommandTaskService;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import com.codepilot.task.PrCommandTaskProducer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixCommandHandlerTest {

    @Test
    void shouldCreateAndEnqueueFixTaskWhenFixIsEnabled() {
        TestContext context = new TestContext(true);
        when(context.prCommandTaskService.createFixTask(context.payload)).thenReturn(task("PENDING"));

        var result = context.handler.handle(context.payload);

        assertThat(result.getId()).isEqualTo(1L);
        verify(context.prCommandTaskProducer).send(1L);
    }

    @Test
    void shouldNotEnqueueExistingTerminalFixTask() {
        TestContext context = new TestContext(true);
        when(context.prCommandTaskService.createFixTask(context.payload)).thenReturn(task("SUCCESS"));

        var result = context.handler.handle(context.payload);

        assertThat(result.getId()).isEqualTo(1L);
        verify(context.prCommandTaskProducer, never()).send(1L);
    }

    @Test
    void shouldCommentAndSkipTaskCreationWhenFixIsDisabled() {
        TestContext context = new TestContext(false);

        var result = context.handler.handle(context.payload);

        assertThat(result.getId()).isNull();
        verify(context.prCommandTaskService, never()).createFixTask(context.payload);
        verify(context.prCommandTaskProducer, never()).send(eq(1L));
        verify(context.githubClient).createPullRequestComment(eq("liche719"), eq("codeAireview"), eq(12), org.mockito.ArgumentMatchers.anyString());
    }

    private PrCommandTask task(String status) {
        PrCommandTask task = new PrCommandTask();
        task.setId(1L);
        task.setStatus(status);
        return task;
    }

    private static class TestContext {

        private final GithubClient githubClient = mock(GithubClient.class);

        private final PrCommandTaskService prCommandTaskService = mock(PrCommandTaskService.class);

        private final PrCommandTaskProducer prCommandTaskProducer = mock(PrCommandTaskProducer.class);

        private final GitHubPullRequestWebhookPayload payload = payload();

        private final FixCommandHandler handler;

        private TestContext(boolean fixEnabled) {
            GithubCommandProperties properties = new GithubCommandProperties();
            properties.setFixEnabled(fixEnabled);
            handler = new FixCommandHandler(
                    properties,
                    githubClient,
                    prCommandTaskService,
                    prCommandTaskProducer
            );
        }

        private static GitHubPullRequestWebhookPayload payload() {
            GitHubPullRequestWebhookPayload payload = new GitHubPullRequestWebhookPayload();
            payload.setOwner("liche719");
            payload.setRepo("codeAireview");
            payload.setPullNumber(12);
            payload.setAction("created");
            return payload;
        }
    }
}
