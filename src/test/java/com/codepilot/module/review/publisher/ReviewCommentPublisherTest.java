package com.codepilot.module.review.publisher;

import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.service.GitHubCommentService;
import com.codepilot.module.review.service.GitHubCommentResult;
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
        when(context.githubCommentService.commentReviewResult(1L))
                .thenReturn(GitHubCommentResult.publishedResult());

        ReviewCommentPublishResult result = context.publisher.publish(task(ReviewCommentMode.SUMMARY_ONLY));

        org.assertj.core.api.Assertions.assertThat(result.published()).isTrue();
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
    void shouldReportSummaryException() {
        TestContext context = new TestContext();
        doThrow(new RuntimeException("summary error"))
                .when(context.githubCommentService)
                .commentReviewResult(1L);

        ReviewCommentPublishResult result = context.publisher.publish(task(ReviewCommentMode.SUMMARY_ONLY));

        verify(context.githubCommentService).commentReviewResult(1L);
        org.assertj.core.api.Assertions.assertThat(result.published()).isFalse();
        org.assertj.core.api.Assertions.assertThat(result.errorMessage()).contains("summary error");
    }

    /**
     * BOTH：摘要**无条件**要发，即使行内已经成功。
     *
     * <p>它承载 R2 的"本次只审了 N/M 个文件"——为了省一条评论而跳过它，
     * 会让部分覆盖看起来像全部通过。</p>
     */
    @Test
    void shouldPublishBothInlineAndSummaryForBothMode() {
        TestContext context = new TestContext();
        when(context.gitHubInlineCommentService.commentInlineIssues(1L))
                .thenReturn(new GitHubInlineCommentResult(3, 0, 0));
        when(context.githubCommentService.commentReviewResult(1L))
                .thenReturn(GitHubCommentResult.publishedResult());

        ReviewCommentPublishResult result = context.publisher.publish(task(ReviewCommentMode.BOTH));

        verify(context.gitHubInlineCommentService).commentInlineIssues(1L);
        verify(context.githubCommentService).commentReviewResult(1L);
        org.assertj.core.api.Assertions.assertThat(result.published()).isTrue();
    }

    /** 行内全失败时，摘要仍然要发——否则一条评论都没有。 */
    @Test
    void shouldStillPublishSummaryWhenInlineFailsInBothMode() {
        TestContext context = new TestContext();
        doThrow(new RuntimeException("inline error"))
                .when(context.gitHubInlineCommentService)
                .commentInlineIssues(1L);
        when(context.githubCommentService.commentReviewResult(1L))
                .thenReturn(GitHubCommentResult.publishedResult());

        ReviewCommentPublishResult result = context.publisher.publish(task(ReviewCommentMode.BOTH));

        verify(context.githubCommentService).commentReviewResult(1L);
        org.assertj.core.api.Assertions.assertThat(result.published()).isTrue();
    }

    /** 行内成功但摘要失败：仍算发布成功，避免整任务重试导致重复行内评论。 */
    @Test
    void shouldReportPublishedInBothModeWhenInlineSucceededButSummaryFailed() {
        TestContext context = new TestContext();
        when(context.gitHubInlineCommentService.commentInlineIssues(1L))
                .thenReturn(new GitHubInlineCommentResult(2, 0, 0));
        doThrow(new RuntimeException("summary error"))
                .when(context.githubCommentService)
                .commentReviewResult(1L);

        ReviewCommentPublishResult result = context.publisher.publish(task(ReviewCommentMode.BOTH));

        org.assertj.core.api.Assertions.assertThat(result.published()).isTrue();
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
