package com.codepilot.module.command.handler;

import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.service.ReviewTaskService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewCommandHandlerTest {

    private final ReviewTaskService reviewTaskService = mock(ReviewTaskService.class);

    private final GithubClient githubClient = mock(GithubClient.class);

    private final ReviewCommandHandler handler = new ReviewCommandHandler(reviewTaskService, githubClient);

    @Test
    void shouldResolveHeadShaBeforeCreatingReviewTaskForIssueCommentCommand() {
        GitHubPullRequestWebhookPayload payload = reviewCommandPayload();
        GithubPullRequestDetail detail = new GithubPullRequestDetail();
        detail.setHeadSha("abc123");
        when(githubClient.getPullRequestDetail("liche719", "codeAireview", 34)).thenReturn(detail);
        when(reviewTaskService.createTask(
                payload.getPrUrl(),
                payload.getTitle(),
                ReviewCommentMode.SUMMARY_ONLY,
                "abc123"
        )).thenReturn(new ReviewCreateResponse(99L, "PENDING"));

        GithubCommandHandleResult result = handler.handle(payload);

        assertThat(result.getId()).isEqualTo(99L);
        verify(reviewTaskService).createTask(
                payload.getPrUrl(),
                payload.getTitle(),
                ReviewCommentMode.SUMMARY_ONLY,
                "abc123"
        );
    }

    @Test
    void shouldReusePayloadHeadShaWithoutFetchingPrDetail() {
        GitHubPullRequestWebhookPayload payload = reviewCommandPayload();
        payload.setHeadSha("payload-sha");
        when(reviewTaskService.createTask(
                payload.getPrUrl(),
                payload.getTitle(),
                ReviewCommentMode.SUMMARY_ONLY,
                "payload-sha"
        )).thenReturn(new ReviewCreateResponse(100L, "PENDING"));

        GithubCommandHandleResult result = handler.handle(payload);

        assertThat(result.getId()).isEqualTo(100L);
        verify(githubClient, never()).getPullRequestDetail("liche719", "codeAireview", 34);
    }

    private GitHubPullRequestWebhookPayload reviewCommandPayload() {
        return GitHubPullRequestWebhookPayload.reviewCommand(
                "created",
                "liche719",
                "codeAireview",
                34,
                "https://github.com/liche719/codeAireview/pull/34",
                "Validate review command",
                1234L,
                "/review",
                "liche719",
                "OWNER",
                "REVIEW",
                "/review",
                false,
                false
        );
    }
}
