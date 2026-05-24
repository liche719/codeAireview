package com.codepilot.module.review.processor;

import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.agent.dto.AiReviewRequest;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.review.assembler.ReviewIssueAssembler;
import com.codepilot.module.review.context.ReviewContextBuilder;
import com.codepilot.module.review.context.ReviewContextSignalExtractor;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

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
                new ReviewContextBuilder(new ReviewContextSignalExtractor())
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
                new ReviewContextBuilder(new ReviewContextSignalExtractor())
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
                new ReviewContextBuilder(new ReviewContextSignalExtractor())
        );
        when(aiReviewService.reviewFile(any(AiReviewRequest.class)))
                .thenThrow(new IllegalArgumentException("bad model output"));

        assertThatThrownBy(() -> reviewer.review(1L, List.of(reviewFile("src/main/java/Demo.java", "+code", false))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI review failed for all reviewable files, failedCount=1")
                .hasMessageContaining("bad model output")
                .hasRootCauseMessage("bad model output");
    }

    private static ReviewFile reviewFile(String path, String patch, boolean skipped) {
        ReviewFile reviewFile = new ReviewFile();
        reviewFile.setFilePath(path);
        reviewFile.setPatch(patch);
        reviewFile.setSkipped(skipped);
        return reviewFile;
    }

    private static AiReviewResult aiReviewResult() {
        AiReviewIssue issue = new AiReviewIssue();
        issue.setLineNumber(1);
        issue.setIssueType("BUG_RISK");
        issue.setSeverity("HIGH");
        issue.setTitle("Bug");
        issue.setDescription("Risky change.");
        issue.setSuggestion("Fix it.");

        AiReviewResult result = new AiReviewResult();
        result.setIssues(List.of(issue));
        return result;
    }
}
