package com.codepilot.module.command.handler;

import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import com.codepilot.module.review.report.ReviewReportFormatter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class HelpCommandHandlerTest {

    @Test
    void shouldPrependBotResponseMarkerToHelpComment() {
        GithubClient githubClient = mock(GithubClient.class);
        HelpCommandHandler handler = new HelpCommandHandler(githubClient);

        GitHubPullRequestWebhookPayload payload = payload();

        handler.handle(payload);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(githubClient).createPullRequestComment(
                eq("liche719"),
                eq("codeAireview"),
                eq(12),
                bodyCaptor.capture()
        );

        assertThat(bodyCaptor.getValue()).contains(ReviewReportFormatter.DEFAULT_COMMENT_MARKER);
        assertThat(bodyCaptor.getValue()).contains("@x-pilotx review");
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
