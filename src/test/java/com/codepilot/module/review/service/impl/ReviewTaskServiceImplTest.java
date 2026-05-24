package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.module.agent.dto.AiReviewRequest;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.git.dto.GithubPrInfo;
import com.codepilot.module.git.parser.GithubPrUrlParser;
import com.codepilot.module.git.policy.GithubRepositoryPolicy;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.assembler.ReviewIssueAssembler;
import com.codepilot.module.review.context.ReviewContextBuilder;
import com.codepilot.module.review.context.ReviewContextSignalExtractor;
import com.codepilot.module.review.creator.ReviewTaskCreator;
import com.codepilot.module.review.failure.ReviewTaskFailureHandler;
import com.codepilot.module.review.planner.ReviewFilePlanner;
import com.codepilot.module.review.processor.ReviewFileReviewer;
import com.codepilot.module.review.processor.ReviewTaskProcessor;
import com.codepilot.module.review.publisher.ReviewCommentPublisher;
import com.codepilot.module.review.queue.ReviewTaskMessageDispatcher;
import com.codepilot.module.review.runner.ReviewTaskRunner;
import com.codepilot.module.review.service.ReviewFileService;
import com.codepilot.module.review.service.ReviewIssueService;
import com.codepilot.module.review.state.ReviewTaskStateManager;
import com.codepilot.module.review.sync.ReviewTaskHeadShaRefresher;
import com.codepilot.task.ReviewTaskProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.retry.context.RetryContextSupport;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
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

    @AfterEach
    void clearRetryContext() {
        RetrySynchronizationManager.clear();
    }

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
    void shouldPersistHeadShaWhenCreatingTaskFromWebhook() {
        TestContext context = new TestContext();
        when(context.githubPrUrlParser.parse("https://github.com/liche719/codeAireview/pull/12"))
                .thenReturn(new GithubPrInfo("liche719", "codeAireview", 12));
        when(context.reviewTaskMapper.insert(any(ReviewTask.class))).thenAnswer(invocation -> {
            ReviewTask task = invocation.getArgument(0);
            task.setId(1L);
            return 1;
        });

        context.service.createTask(
                "https://github.com/liche719/codeAireview/pull/12",
                "Add webhook support",
                ReviewCommentMode.SUMMARY_ONLY,
                " abc123 "
        );

        verify(context.reviewTaskMapper).insert(context.taskCaptor.capture());
        assertThat(context.taskCaptor.getValue().getHeadSha()).isEqualTo("abc123");
    }

    @Test
    void shouldReuseExistingReviewTaskForSameHeadShaAndCommentMode() {
        TestContext context = new TestContext();
        when(context.githubPrUrlParser.parse("https://github.com/liche719/codeAireview/pull/12"))
                .thenReturn(new GithubPrInfo("liche719", "codeAireview", 12));
        ReviewTask existingTask = new ReviewTask();
        existingTask.setId(99L);
        existingTask.setStatus("SUCCESS");
        existingTask.setHeadSha("abc123");
        existingTask.setReviewCommentMode("SUMMARY_ONLY");
        when(context.reviewTaskMapper.selectList(any())).thenReturn(List.of(existingTask));

        ReviewCreateResponse response = context.service.createTask(
                "https://github.com/liche719/codeAireview/pull/12",
                "Add webhook support",
                ReviewCommentMode.SUMMARY_ONLY,
                " abc123 "
        );

        assertThat(response.getTaskId()).isEqualTo(99L);
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(context.reviewTaskMapper, never()).insert(any(ReviewTask.class));
        verify(context.reviewTaskProducer, never()).send(anyLong());
    }

    @Test
    void shouldCreateNewReviewTaskWhenReusableTaskOnlyFailed() {
        TestContext context = new TestContext();
        when(context.githubPrUrlParser.parse("https://github.com/liche719/codeAireview/pull/12"))
                .thenReturn(new GithubPrInfo("liche719", "codeAireview", 12));
        when(context.reviewTaskMapper.selectList(any())).thenReturn(List.of());
        when(context.reviewTaskMapper.insert(any(ReviewTask.class))).thenAnswer(invocation -> {
            ReviewTask task = invocation.getArgument(0);
            task.setId(100L);
            return 1;
        });

        ReviewCreateResponse response = context.service.createTask(
                "https://github.com/liche719/codeAireview/pull/12",
                "Retry review",
                ReviewCommentMode.SUMMARY_ONLY,
                "abc123"
        );

        assertThat(response.getTaskId()).isEqualTo(100L);
        assertThat(response.getStatus()).isEqualTo("PENDING");
        verify(context.reviewTaskMapper).insert(any(ReviewTask.class));
    }

    @Test
    void shouldReuseConcurrentlyCreatedReviewTaskWhenUniqueIndexRejectsDuplicate() {
        TestContext context = new TestContext();
        when(context.githubPrUrlParser.parse("https://github.com/liche719/codeAireview/pull/12"))
                .thenReturn(new GithubPrInfo("liche719", "codeAireview", 12));
        ReviewTask existingTask = new ReviewTask();
        existingTask.setId(99L);
        existingTask.setStatus("PENDING");
        existingTask.setHeadSha("abc123");
        existingTask.setReviewCommentMode("SUMMARY_ONLY");
        when(context.reviewTaskMapper.selectList(any())).thenReturn(List.of(), List.of(existingTask));
        when(context.reviewTaskMapper.insert(any(ReviewTask.class))).thenThrow(new DuplicateKeyException("duplicate"));

        ReviewCreateResponse response = context.service.createTask(
                "https://github.com/liche719/codeAireview/pull/12",
                "Add webhook support",
                ReviewCommentMode.SUMMARY_ONLY,
                "abc123"
        );

        assertThat(response.getTaskId()).isEqualTo(99L);
        assertThat(response.getStatus()).isEqualTo("PENDING");
        verify(context.reviewTaskProducer, never()).send(anyLong());
    }

    @Test
    void shouldRejectTaskCreationWhenRepositoryIsNotAllowed() {
        TestContext context = new TestContext();
        when(context.githubPrUrlParser.parse("https://github.com/evil/repo/pull/12"))
                .thenReturn(new GithubPrInfo("evil", "repo", 12));
        doThrow(new com.codepilot.common.exception.BusinessException("GitHub repository is not allowed: evil/repo"))
                .when(context.githubRepositoryPolicy)
                .assertAllowed("evil", "repo");

        assertThatThrownBy(() -> context.service.createTask("https://github.com/evil/repo/pull/12"))
                .isInstanceOf(com.codepilot.common.exception.BusinessException.class)
                .hasMessage("GitHub repository is not allowed: evil/repo");

        verify(context.reviewTaskMapper, never()).insert(any(ReviewTask.class));
        verify(context.reviewTaskProducer, never()).send(anyLong());
    }

    @Test
    void shouldPublishReviewCommentAfterSuccessfulTask() {
        TestContext context = new TestContext();
        context.stubTask(ReviewCommentMode.INLINE_ONLY);
        context.stubEmptyReviewFlow();

        context.service.processTask(1L);

        verify(context.reviewCommentPublisher).publish(context.publishTaskCaptor.capture());
        ReviewTask publishedTask = context.publishTaskCaptor.getValue();
        assertThat(publishedTask.getId()).isEqualTo(1L);
        assertThat(publishedTask.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void shouldMarkTaskFailedWhenAiReviewFails() {
        TestContext context = new TestContext();
        context.stubTask(ReviewCommentMode.SUMMARY_ONLY);
        context.stubEmptyReviewFlow();
        when(context.githubClient.listPullRequestFiles("liche719", "codeAireview", 123))
                .thenReturn(List.of(context.changedFile()));
        when(context.aiReviewService.reviewFile(any(AiReviewRequest.class)))
                .thenThrow(new IllegalArgumentException("bad ai json"));

        assertThatThrownBy(() -> context.service.processTask(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("review task failed, taskId=1")
                .hasMessageContaining("errorType=IllegalStateException");

        verify(context.reviewTaskMapper, org.mockito.Mockito.atLeastOnce()).updateById(context.taskCaptor.capture());
        ReviewTask lastTaskUpdate = context.taskCaptor.getAllValues().getLast();
        assertThat(lastTaskUpdate.getStatus()).isEqualTo("FAILED");
        assertThat(lastTaskUpdate.getErrorMessage()).contains("AI review failed for all reviewable files");
        assertThat(lastTaskUpdate.getErrorMessage()).contains("bad ai json");
        assertThat(lastTaskUpdate.getHeadSha()).isEqualTo("head-sha");
        verify(context.reviewCommentPublisher, never()).publish(any(ReviewTask.class));
        verify(context.reviewIssueService, never()).saveBatch(anyList());
    }

    @Test
    void shouldRedactSecretsBeforePersistingTaskFailure() {
        TestContext context = new TestContext();
        context.stubTask(ReviewCommentMode.SUMMARY_ONLY);
        context.stubEmptyReviewFlow();
        when(context.githubClient.listPullRequestFiles("liche719", "codeAireview", 123))
                .thenThrow(new IllegalStateException("github token=ghp_123456789012345678901234567890123456 rejected"));

        Throwable thrown = catchThrowable(() -> context.service.processTask(1L));
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("review task failed, taskId=1");
        assertThat(thrown.getCause()).isNull();

        verify(context.reviewTaskMapper, org.mockito.Mockito.atLeastOnce()).updateById(context.taskCaptor.capture());
        ReviewTask lastTaskUpdate = context.taskCaptor.getAllValues().getLast();
        assertThat(lastTaskUpdate.getStatus()).isEqualTo("FAILED");
        assertThat(lastTaskUpdate.getErrorMessage())
                .contains("[REDACTED]")
                .doesNotContain("ghp_123456789012345678901234567890123456");
        verify(context.reviewCommentPublisher, never()).publish(any(ReviewTask.class));
    }

    @Test
    void shouldKeepTaskRunningBeforeFinalRetryAttempt() {
        TestContext context = new TestContext();
        context.stubTask(ReviewCommentMode.SUMMARY_ONLY);
        context.stubEmptyReviewFlow();
        when(context.githubClient.listPullRequestFiles("liche719", "codeAireview", 123))
                .thenThrow(new IllegalStateException("github temporary error"));
        ReflectionTestUtils.setField(context.reviewTaskFailureHandler, "rabbitRetryMaxAttempts", 3);
        registerRetryContext(0);

        assertThatThrownBy(() -> context.service.processTask(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("review task failed, taskId=1");

        verify(context.reviewTaskMapper, org.mockito.Mockito.atLeastOnce()).updateById(context.taskCaptor.capture());
        ReviewTask lastTaskUpdate = context.taskCaptor.getAllValues().getLast();
        assertThat(lastTaskUpdate.getStatus()).isEqualTo("RUNNING");
        assertThat(lastTaskUpdate.getErrorMessage()).contains("github temporary error");
        assertThat(lastTaskUpdate.getFinishedAt()).isNull();
    }

    @Test
    void shouldMarkTaskFailedOnFinalRetryAttempt() {
        TestContext context = new TestContext();
        context.stubTask(ReviewCommentMode.SUMMARY_ONLY);
        context.stubEmptyReviewFlow();
        when(context.githubClient.listPullRequestFiles("liche719", "codeAireview", 123))
                .thenThrow(new IllegalStateException("github final error"));
        ReflectionTestUtils.setField(context.reviewTaskFailureHandler, "rabbitRetryMaxAttempts", 3);
        registerRetryContext(2);

        assertThatThrownBy(() -> context.service.processTask(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("review task failed, taskId=1");

        verify(context.reviewTaskMapper, org.mockito.Mockito.atLeastOnce()).updateById(context.taskCaptor.capture());
        ReviewTask lastTaskUpdate = context.taskCaptor.getAllValues().getLast();
        assertThat(lastTaskUpdate.getStatus()).isEqualTo("FAILED");
        assertThat(lastTaskUpdate.getErrorMessage()).contains("github final error");
        assertThat(lastTaskUpdate.getFinishedAt()).isNotNull();
    }

    private void registerRetryContext(int retryCount) {
        RetryContextSupport context = new RetryContextSupport(null);
        for (int i = 0; i < retryCount; i++) {
            context.registerThrowable(new RuntimeException("retry-" + i));
        }
        RetrySynchronizationManager.register(context);
    }

    private static class TestContext {

        private final ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);

        private final GithubPrUrlParser githubPrUrlParser = mock(GithubPrUrlParser.class);

        private final GithubClient githubClient = mock(GithubClient.class);

        private final ReviewFileService reviewFileService = mock(ReviewFileService.class);

        private final ReviewIssueService reviewIssueService = mock(ReviewIssueService.class);

        private final AiReviewService aiReviewService = mock(AiReviewService.class);

        private final ReviewTaskProducer reviewTaskProducer = mock(ReviewTaskProducer.class);

        private final ReviewTaskMessageDispatcher reviewTaskMessageDispatcher =
                new ReviewTaskMessageDispatcher(reviewTaskProducer);

        private final ReviewProperties reviewProperties = new ReviewProperties();

        private final ReviewFilePlanner reviewFilePlanner = new ReviewFilePlanner(reviewProperties);

        private final ReviewIssueAssembler reviewIssueAssembler = new ReviewIssueAssembler();

        private final ReviewFileReviewer reviewFileReviewer = new ReviewFileReviewer(
                aiReviewService,
                reviewIssueAssembler,
                new ReviewContextBuilder(new ReviewContextSignalExtractor()),
                reviewProperties
        );

        private final ReviewTaskProcessor reviewTaskProcessor = new ReviewTaskProcessor(
                githubClient,
                reviewFilePlanner,
                reviewFileService,
                reviewIssueService,
                reviewIssueAssembler,
                reviewFileReviewer
        );

        private final ReviewCommentPublisher reviewCommentPublisher = mock(ReviewCommentPublisher.class);

        private final GithubRepositoryPolicy githubRepositoryPolicy = mock(GithubRepositoryPolicy.class);

        private final ReviewTaskStateManager reviewTaskStateManager = new ReviewTaskStateManager(reviewTaskMapper);

        private final ReviewTaskFailureHandler reviewTaskFailureHandler =
                new ReviewTaskFailureHandler(reviewTaskStateManager);

        private final ReviewTaskHeadShaRefresher reviewTaskHeadShaRefresher =
                new ReviewTaskHeadShaRefresher(githubClient, reviewTaskStateManager);

        private final ReviewTaskCreator reviewTaskCreator = new ReviewTaskCreator(
                githubPrUrlParser,
                githubRepositoryPolicy,
                reviewTaskMapper
        );

        private final ReviewTaskRunner reviewTaskRunner = new ReviewTaskRunner(
                reviewTaskFailureHandler,
                reviewTaskHeadShaRefresher,
                reviewTaskProcessor,
                reviewCommentPublisher,
                reviewTaskStateManager
        );

        private final org.mockito.ArgumentCaptor<ReviewTask> taskCaptor =
                org.mockito.ArgumentCaptor.forClass(ReviewTask.class);

        private final org.mockito.ArgumentCaptor<ReviewTask> publishTaskCaptor =
                org.mockito.ArgumentCaptor.forClass(ReviewTask.class);

        private final ReviewTaskServiceImpl service;

        private TestContext() {
            service = new ReviewTaskServiceImpl(
                    reviewTaskCreator,
                    reviewTaskMessageDispatcher,
                    reviewTaskRunner
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
            when(githubClient.getPullRequestDetail("liche719", "codeAireview", 123)).thenReturn(prDetail());
            when(githubClient.listPullRequestFiles("liche719", "codeAireview", 123)).thenReturn(List.of());
        }

        private void stubEmptyReviewFlow() {
            when(reviewFileService.remove(any(LambdaQueryWrapper.class))).thenReturn(true);
            when(reviewIssueService.remove(any(LambdaQueryWrapper.class))).thenReturn(true);
            when(reviewFileService.saveBatch(anyList())).thenReturn(true);
            when(reviewTaskMapper.updateById(any(ReviewTask.class))).thenReturn(1);
        }

        private GithubChangedFile changedFile() {
            GithubChangedFile changedFile = new GithubChangedFile();
            changedFile.setFilename("src/main/java/Demo.java");
            changedFile.setStatus("modified");
            changedFile.setPatch("""
                    @@ -1,1 +1,2 @@
                     class Demo {
                    +  void run() {}
                    """);
            changedFile.setAdditions(1);
            changedFile.setDeletions(0);
            return changedFile;
        }

        private GithubPullRequestDetail prDetail() {
            GithubPullRequestDetail detail = new GithubPullRequestDetail();
            detail.setHeadSha("head-sha");
            return detail;
        }
    }
}
