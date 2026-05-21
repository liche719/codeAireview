package com.codepilot.module.review.publisher;

import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.service.GitHubCommentService;
import com.codepilot.module.review.service.GitHubInlineCommentResult;
import com.codepilot.module.review.service.GitHubInlineCommentService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewCommentPublisherTest {

    @Test
    void shouldPublishSummaryForSummaryOnlyTask() {
        TestContext context = new TestContext();

        context.publisher.publish(task(ReviewCommentMode.SUMMARY_ONLY));

        verify(context.githubCommentService).commentReviewResult(1L);
        verify(context.gitHubInlineCommentService, never()).commentInlineIssues(anyLong());
    }

    @Test
    void shouldPublishInlineOnlyWithoutSummaryWhenInlineSucceeds() {
        TestContext context = new TestContext();
        when(context.gitHubInlineCommentService.commentInlineIssues(1L))
                .thenReturn(new GitHubInlineCommentResult(1, 0, 0));

        context.publisher.publish(task(ReviewCommentMode.INLINE_ONLY));

        verify(context.gitHubInlineCommentService).commentInlineIssues(1L);
        verify(context.githubCommentService, never()).commentReviewResult(anyLong());
    }

    @Test
    void shouldFallbackToSummaryWhenInlineProducesNoComment() {
        TestContext context = new TestContext();
        when(context.gitHubInlineCommentService.commentInlineIssues(1L))
                .thenReturn(new GitHubInlineCommentResult(0, 0, 0));

        context.publisher.publish(task(ReviewCommentMode.INLINE_ONLY));

        verify(context.gitHubInlineCommentService).commentInlineIssues(1L);
        verify(context.githubCommentService).commentReviewResult(1L);
    }

    @Test
    void shouldFallbackToSummaryWhenInlineThrows() {
        TestContext context = new TestContext();
        doThrow(new RuntimeException("inline error"))
                .when(context.gitHubInlineCommentService)
                .commentInlineIssues(1L);

        context.publisher.publish(task(ReviewCommentMode.INLINE_ONLY));

        verify(context.gitHubInlineCommentService).commentInlineIssues(1L);
        verify(context.githubCommentService).commentReviewResult(1L);
    }

    @Test
    void shouldSwallowSummaryException() {
        TestContext context = new TestContext();
        doThrow(new RuntimeException("summary error"))
                .when(context.githubCommentService)
                .commentReviewResult(1L);

        context.publisher.publish(task(ReviewCommentMode.SUMMARY_ONLY));

        verify(context.githubCommentService).commentReviewResult(1L);
    }

    private ReviewTask task(ReviewCommentMode reviewCommentMode) {
        ReviewTask task = new ReviewTask();
        task.setId(1L);
        task.setReviewCommentMode(reviewCommentMode.name());
        return task;
    }

    private static class TestContext {

        private final GitHubCommentService githubCommentService = mock(GitHubCommentService.class);

        private final GitHubInlineCommentService gitHubInlineCommentService = mock(GitHubInlineCommentService.class);

        private final ReviewCommentPublisher publisher = new ReviewCommentPublisher(
                githubCommentService,
                gitHubInlineCommentService
        );
    }
}
