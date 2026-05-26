package com.codepilot.module.review.processor;

import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.agent.dto.AiReviewRequest;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.review.assembler.ReviewIssueAssembler;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.context.ReviewContextBuilder;
import com.codepilot.module.review.context.ReviewContextRelationshipExtractor;
import com.codepilot.module.review.context.ReviewContextSignalExtractor;
import com.codepilot.module.review.diff.DiffLineMapper;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewFileReviewerTest {

    @Test
    void shouldReviewOnlyNonSkippedFilesWithFullChangedFileList() {
        AiReviewService aiReviewService = mock(AiReviewService.class);
        ReviewFileReviewer reviewer = new ReviewFileReviewer(
                aiReviewService,
                new ReviewIssueAssembler(),
                new ReviewIssueLocationGuard(new DiffLineMapper()),
                new ReviewIssuePatchVerifier(new DiffLineMapper()),
                new ReviewContextBuilder(new ReviewContextSignalExtractor(), new ReviewContextRelationshipExtractor()),
                new ReviewFindingRanker(),
                new ReviewProperties()
        );
        when(aiReviewService.reviewFile(any(AiReviewRequest.class)))
                .thenReturn(aiReviewResult());
        ArgumentCaptor<AiReviewRequest> requestCaptor = ArgumentCaptor.forClass(AiReviewRequest.class);

        var issues = reviewer.review(1L, List.of(
                reviewFile("src/main/java/Demo.java", "+code", false),
                reviewFile("package-lock.json", "+lock", true)
        ));

        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().getTaskId()).isEqualTo(1L);
        assertThat(issues.getFirst().getSeverity()).isEqualTo("HIGH");
        verify(aiReviewService).reviewFile(requestCaptor.capture());
        AiReviewRequest request = requestCaptor.getValue();
        assertThat(request.taskId()).isEqualTo(1L);
        assertThat(request.filePath()).isEqualTo("src/main/java/Demo.java");
        assertThat(request.patch()).isEqualTo("+code");
        assertThat(request.allChangedFiles())
                .containsExactly("src/main/java/Demo.java", "package-lock.json");
        assertThat(request.context().totalFileCount()).isEqualTo(2);
        assertThat(request.context().reviewableFileCount()).isEqualTo(1);
        assertThat(request.context().skippedFileCount()).isEqualTo(1);
        assertThat(request.context().skippedFiles())
                .singleElement()
                .satisfies(skippedFile -> assertThat(skippedFile.filePath()).isEqualTo("package-lock.json"));
        verify(aiReviewService, never()).reviewFile(argThat(argument ->
                argument != null && "package-lock.json".equals(argument.filePath())));
    }

    @Test
    void shouldContinueWhenSingleFileReviewFailsButOtherFilesSucceed() {
        AiReviewService aiReviewService = mock(AiReviewService.class);
        ReviewFileReviewer reviewer = new ReviewFileReviewer(
                aiReviewService,
                new ReviewIssueAssembler(),
                new ReviewIssueLocationGuard(new DiffLineMapper()),
                new ReviewIssuePatchVerifier(new DiffLineMapper()),
                new ReviewContextBuilder(new ReviewContextSignalExtractor(), new ReviewContextRelationshipExtractor()),
                new ReviewFindingRanker(),
                new ReviewProperties()
        );
        when(aiReviewService.reviewFile(argThat(request ->
                request != null && "src/main/java/Broken.java".equals(request.filePath()))))
                .thenThrow(new IllegalArgumentException("bad model output"));
        when(aiReviewService.reviewFile(argThat(request ->
                request != null && "src/main/java/Healthy.java".equals(request.filePath()))))
                .thenReturn(aiReviewResult());

        List<ReviewIssue> issues = reviewer.review(1L, List.of(
                reviewFile("src/main/java/Broken.java", "+broken", false),
                reviewFile("src/main/java/Healthy.java", "+healthy", false)
        ));

        assertThat(issues)
                .anySatisfy(issue -> {
                    assertThat(issue.getFilePath()).isEqualTo("src/main/java/Broken.java");
                    assertThat(issue.getIssueType()).isEqualTo("AI_REVIEW_FAILED");
                    assertThat(issue.getSeverity()).isEqualTo("MEDIUM");
                    assertThat(issue.getSource()).isEqualTo("SYSTEM");
                    assertThat(issue.getDescription()).contains("bad model output");
                })
                .anySatisfy(issue -> {
                    assertThat(issue.getFilePath()).isEqualTo("src/main/java/Healthy.java");
                    assertThat(issue.getIssueType()).isEqualTo("BUG_RISK");
                });
    }

    @Test
    void shouldFailTaskWhenAllReviewableFilesFail() {
        AiReviewService aiReviewService = mock(AiReviewService.class);
        ReviewFileReviewer reviewer = new ReviewFileReviewer(
                aiReviewService,
                new ReviewIssueAssembler(),
                new ReviewIssueLocationGuard(new DiffLineMapper()),
                new ReviewIssuePatchVerifier(new DiffLineMapper()),
                new ReviewContextBuilder(new ReviewContextSignalExtractor(), new ReviewContextRelationshipExtractor()),
                new ReviewFindingRanker(),
                new ReviewProperties()
        );
        when(aiReviewService.reviewFile(any(AiReviewRequest.class)))
                .thenThrow(new IllegalArgumentException("bad model output"));

        assertThatThrownBy(() -> reviewer.review(1L, List.of(reviewFile("src/main/java/Demo.java", "+code", false))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI review failed for all reviewable files, failedCount=1")
                .hasMessageContaining("bad model output")
                .hasRootCauseMessage("bad model output");
    }

    @Test
    void shouldReviewFilesInParallelButKeepIssueOrderStable() throws Exception {
        AiReviewService aiReviewService = mock(AiReviewService.class);
        ReviewProperties reviewProperties = new ReviewProperties();
        reviewProperties.setMaxParallelFiles(2);
        ReviewFileReviewer reviewer = new ReviewFileReviewer(
                aiReviewService,
                new ReviewIssueAssembler(),
                new ReviewIssueLocationGuard(new DiffLineMapper()),
                new ReviewIssuePatchVerifier(new DiffLineMapper()),
                new ReviewContextBuilder(new ReviewContextSignalExtractor(), new ReviewContextRelationshipExtractor()),
                new ReviewFindingRanker(),
                reviewProperties
        );
        CountDownLatch slowFileStarted = new CountDownLatch(1);
        CountDownLatch releaseSlowFile = new CountDownLatch(1);
        AtomicBoolean fastFileStartedBeforeSlowReleased = new AtomicBoolean(false);
        when(aiReviewService.reviewFile(any(AiReviewRequest.class)))
                .thenAnswer(invocation -> {
                    AiReviewRequest request = invocation.getArgument(0);
                    if ("src/main/java/Slow.java".equals(request.filePath())) {
                        slowFileStarted.countDown();
                        releaseSlowFile.await(1, TimeUnit.SECONDS);
                        return aiReviewResult("Slow issue");
                    }
                    slowFileStarted.await(1, TimeUnit.SECONDS);
                    fastFileStartedBeforeSlowReleased.set(releaseSlowFile.getCount() > 0);
                    releaseSlowFile.countDown();
                    return aiReviewResult("Fast issue");
                });

        List<ReviewIssue> issues = reviewer.review(1L, List.of(
                reviewFile("src/main/java/Slow.java", "+slow", false),
                reviewFile("src/main/java/Fast.java", "+fast", false)
        ));

        assertThat(fastFileStartedBeforeSlowReleased).isTrue();
        assertThat(issues)
                .extracting(ReviewIssue::getTitle)
                .containsExactly("Slow issue", "Fast issue");
    }

    @Test
    void shouldUseSemanticPlanPriorityForReviewScheduling() {
        AiReviewService aiReviewService = mock(AiReviewService.class);
        ReviewProperties reviewProperties = new ReviewProperties();
        reviewProperties.setMaxParallelFiles(1);
        ReviewFileReviewer reviewer = new ReviewFileReviewer(
                aiReviewService,
                new ReviewIssueAssembler(),
                new ReviewIssueLocationGuard(new DiffLineMapper()),
                new ReviewIssuePatchVerifier(new DiffLineMapper()),
                new ReviewContextBuilder(new ReviewContextSignalExtractor(), new ReviewContextRelationshipExtractor()),
                new ReviewFindingRanker(),
                reviewProperties
        );
        when(aiReviewService.reviewFile(any(AiReviewRequest.class)))
                .thenAnswer(invocation -> aiReviewResult(((AiReviewRequest) invocation.getArgument(0)).filePath()));
        ArgumentCaptor<AiReviewRequest> requestCaptor = ArgumentCaptor.forClass(AiReviewRequest.class);

        reviewer.review(1L, List.of(
                reviewFile("README.md", "+docs", false),
                reviewFile("src/main/resources/mapper/UserMapper.xml", "+select * from user", false)
        ));

        verify(aiReviewService, org.mockito.Mockito.times(2)).reviewFile(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues())
                .extracting(AiReviewRequest::filePath)
                .containsExactly("src/main/resources/mapper/UserMapper.xml", "README.md");
    }

    private static ReviewFile reviewFile(String path, String patch, boolean skipped) {
        ReviewFile reviewFile = new ReviewFile();
        reviewFile.setFilePath(path);
        reviewFile.setPatch(patch);
        reviewFile.setSkipped(skipped);
        return reviewFile;
    }

    private static AiReviewResult aiReviewResult() {
        return aiReviewResult("Bug");
    }

    private static AiReviewResult aiReviewResult(String title) {
        AiReviewIssue issue = new AiReviewIssue();
        issue.setLineNumber(1);
        issue.setIssueType("BUG_RISK");
        issue.setSeverity("HIGH");
        issue.setTitle(title);
        issue.setDescription("Risky change can break the reviewed behavior.");
        issue.setSuggestion("Fix the risky change before merging.");

        AiReviewResult result = new AiReviewResult();
        result.setIssues(List.of(issue));
        return result;
    }
}
