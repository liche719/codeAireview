package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPrInfo;
import com.codepilot.module.git.parser.GithubPrUrlParser;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.service.GitHubInlineCommentResult;
import com.codepilot.module.review.service.GitHubCommentService;
import com.codepilot.module.review.service.GitHubInlineCommentService;
import com.codepilot.module.review.service.ReviewFileService;
import com.codepilot.module.review.service.ReviewIssueService;
import com.codepilot.task.ReviewTaskProducer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewTaskServiceImplTest {

    @Test
    void shouldPersistInlineOnlyModeWhenCreatingTask() {
        TestContext context = new TestContext();
        when(context.githubPrUrlParser.parse("https://github.com/liche719/codeAireview/pull/12"))
                .thenReturn(new GithubPrInfo("liche719", "codeAireview", 12));
        when(context.reviewTaskMapper.insert(any(ReviewTask.class))).thenAnswer(invocation -> {
            ReviewTask task = invocation.getArgument(0);
            task.setId(1L);
            return 1;
        });

        ReviewCreateResponse response = context.service.createTask(
                "https://github.com/liche719/codeAireview/pull/12",
                "Add webhook support",
                ReviewCommentMode.INLINE_ONLY
        );

        assertThat(response.getTaskId()).isEqualTo(1L);
        verify(context.reviewTaskMapper).insert(context.taskCaptor.capture());
        assertThat(context.taskCaptor.getValue().getReviewCommentMode()).isEqualTo("INLINE_ONLY");
    }

    @Test
    void shouldCallInlineCommentServiceForInlineOnlyTask() {
        TestContext context = new TestContext();
        context.stubTask(ReviewCommentMode.INLINE_ONLY);
        context.stubEmptyReviewFlow();
        when(context.gitHubInlineCommentService.commentInlineIssues(1L))
                .thenReturn(new GitHubInlineCommentResult(1, 0, 0));

        context.service.processTask(1L);

        verify(context.gitHubInlineCommentService).commentInlineIssues(1L);
        verify(context.githubCommentService, never()).commentReviewResult(anyLong());
    }

    @Test
    void shouldCallSummaryCommentServiceForSummaryOnlyTask() {
        TestContext context = new TestContext();
        context.stubTask(ReviewCommentMode.SUMMARY_ONLY);
        context.stubEmptyReviewFlow();

        context.service.processTask(1L);

        verify(context.githubCommentService).commentReviewResult(1L);
        verify(context.gitHubInlineCommentService, never()).commentInlineIssues(anyLong());
    }

    @Test
    void shouldFallbackToSummaryWhenInlineReviewProducesNoComment() {
        TestContext context = new TestContext();
        context.stubTask(ReviewCommentMode.INLINE_ONLY);
        context.stubEmptyReviewFlow();
        when(context.gitHubInlineCommentService.commentInlineIssues(1L))
                .thenReturn(new GitHubInlineCommentResult(0, 0, 0));

        context.service.processTask(1L);

        verify(context.gitHubInlineCommentService).commentInlineIssues(1L);
        verify(context.githubCommentService).commentReviewResult(1L);
    }

    @Test
    void shouldFallbackToSummaryWhenInlineReviewThrows() {
        TestContext context = new TestContext();
        context.stubTask(ReviewCommentMode.INLINE_ONLY);
        context.stubEmptyReviewFlow();
        doThrow(new RuntimeException("inline error"))
                .when(context.gitHubInlineCommentService)
                .commentInlineIssues(1L);

        context.service.processTask(1L);

        verify(context.gitHubInlineCommentService).commentInlineIssues(1L);
        verify(context.githubCommentService).commentReviewResult(1L);
    }

    private static class TestContext {

        private final ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);

        private final GithubPrUrlParser githubPrUrlParser = mock(GithubPrUrlParser.class);

        private final GithubClient githubClient = mock(GithubClient.class);

        private final ReviewFileService reviewFileService = mock(ReviewFileService.class);

        private final ReviewIssueService reviewIssueService = mock(ReviewIssueService.class);

        private final AiReviewService aiReviewService = mock(AiReviewService.class);

        private final GitHubCommentService githubCommentService = mock(GitHubCommentService.class);

        private final GitHubInlineCommentService gitHubInlineCommentService = mock(GitHubInlineCommentService.class);

        private final ReviewTaskProducer reviewTaskProducer = mock(ReviewTaskProducer.class);

        private final ReviewProperties reviewProperties = new ReviewProperties();

        private final org.mockito.ArgumentCaptor<ReviewTask> taskCaptor =
                org.mockito.ArgumentCaptor.forClass(ReviewTask.class);

        private final ReviewTaskServiceImpl service;

        private TestContext() {
            service = new ReviewTaskServiceImpl(
                    githubPrUrlParser,
                    githubClient,
                    reviewFileService,
                    reviewIssueService,
                    aiReviewService,
                    githubCommentService,
                    gitHubInlineCommentService,
                    reviewTaskProducer,
                    reviewProperties
            );
            ReflectionTestUtils.setField(service, "baseMapper", reviewTaskMapper);
        }

        private void stubTask(ReviewCommentMode reviewCommentMode) {
            ReviewTask task = new ReviewTask();
            task.setId(1L);
            task.setRepoOwner("liche719");
            task.setRepoName("codeAireview");
            task.setPrNumber(123);
            task.setReviewCommentMode(reviewCommentMode.name());
            when(reviewTaskMapper.selectById(1L)).thenReturn(task);
            when(githubClient.listPullRequestFiles("liche719", "codeAireview", 123)).thenReturn(List.of());
        }

        private void stubEmptyReviewFlow() {
            when(reviewFileService.remove(any(LambdaQueryWrapper.class))).thenReturn(true);
            when(reviewIssueService.remove(any(LambdaQueryWrapper.class))).thenReturn(true);
            when(reviewFileService.saveBatch(anyList())).thenReturn(true);
            when(reviewTaskMapper.updateById(any(ReviewTask.class))).thenReturn(1);
        }
    }
}
