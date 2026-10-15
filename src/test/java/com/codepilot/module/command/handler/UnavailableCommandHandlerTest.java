package com.codepilot.module.command.handler;

import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import com.codepilot.module.review.report.CommentMarkers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UnavailableCommandHandlerTest {

    @Test
    void shouldPrependBotResponseMarkerToUnavailableComment() {
        GithubClient githubClient = mock(GithubClient.class);
        UnavailableCommandHandler handler = new UnavailableCommandHandler(githubClient);

        GitHubPullRequestWebhookPayload payload = payload();

        handler.handle(payload);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(githubClient).createPullRequestComment(
                eq("liche719"),
                eq("codeAireview"),
                eq(12),
                bodyCaptor.capture()
        );

        // 命令提示必须用**自己的**身份标记，不能复用审查摘要标记，
        // 否则它会被下一次摘要更新命中并覆盖。
        assertThat(bodyCaptor.getValue()).contains(CommentMarkers.COMMAND_NOTICE);
        assertThat(bodyCaptor.getValue()).doesNotContain(CommentMarkers.REVIEW_SUMMARY);
        assertThat(bodyCaptor.getValue()).contains("当前暂时不可用");
    }

    private GitHubPullRequestWebhookPayload payload() {
        GitHubPullRequestWebhookPayload payload = new GitHubPullRequestWebhookPayload();
        payload.setOwner("liche719");
        payload.setRepo("codeAireview");
        payload.setPullNumber(12);
        payload.setAction("created");
        return payload;
    }
}
