package com.codepilot.module.review.processor;

import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.agent.dto.AiReviewRequest;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.review.assembler.ReviewIssueAssembler;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.context.ReviewContextBuilder;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.planner.ReviewFilePlanner;
import com.codepilot.module.review.service.ReviewFileService;
import com.codepilot.module.review.service.ReviewIssueService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewTaskProcessorTest {

    @Test
    void shouldProcessChangedFilesAndReturnRiskSummary() {
        TestContext context = new TestContext();
        when(context.githubClient.listPullRequestFiles("liche719", "codeAireview", 12))
                .thenReturn(List.of(
                        changedFile("src/main/java/Demo.java", """
                                @@ -1,1 +1,2 @@
                                 class Demo {
                                +  void run() {}
                                """),
                        changedFile("package-lock.json", "+lock")
                ));
        when(context.reviewFileService.saveBatch(anyList())).thenReturn(true);
        when(context.reviewIssueService.saveBatch(anyList())).thenReturn(true);
        when(context.aiReviewService.reviewFile(any(AiReviewRequest.class)))
                .thenReturn(aiReviewResult());

        ReviewTaskProcessingResult result = context.processor.process(task());

        assertThat(result.totalFiles()).isEqualTo(2);
        assertThat(result.totalIssues()).isEqualTo(1);
        assertThat(result.riskLevel()).isEqualTo("HIGH");
        verify(context.aiReviewService).reviewFile(context.aiReviewRequestCaptor.capture());
        AiReviewRequest request = context.aiReviewRequestCaptor.getValue();
        assertThat(request.taskId()).isEqualTo(7L);
        assertThat(request.filePath()).isEqualTo("src/main/java/Demo.java");
        assertThat(request.allChangedFiles())
                .containsExactly("src/main/java/Demo.java", "package-lock.json");
        assertThat(request.context().totalFileCount()).isEqualTo(2);
        assertThat(request.context().reviewableFileCount()).isEqualTo(1);
        assertThat(request.context().skippedFileCount()).isEqualTo(1);
        assertThat(request.context().totalAdditions()).isEqualTo(2);
        assertThat(request.context().totalDeletions()).isZero();
        assertThat(request.context().skippedFiles())
                .singleElement()
                .satisfies(skippedFile -> {
                    assertThat(skippedFile.filePath()).isEqualTo("package-lock.json");
                    assertThat(skippedFile.reason()).isEqualTo("file type or generated path skipped");
                });
        verify(context.reviewFileService).saveBatch(context.reviewFilesCaptor.capture());
        assertThat(context.reviewFilesCaptor.getValue())
                .extracting(ReviewFile::getSkipped)
                .containsExactly(false, true);
        verify(context.reviewIssueService).saveBatch(context.reviewIssuesCaptor.capture());
        assertThat(context.reviewIssuesCaptor.getValue())
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.getTaskId()).isEqualTo(7L);
                    assertThat(issue.getFilePath()).isEqualTo("src/main/java/Demo.java");
                    assertThat(issue.getSeverity()).isEqualTo("HIGH");
                });
    }

    private static ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(7L);
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(12);
        return task;
    }

    private static GithubChangedFile changedFile(String filename, String patch) {
        GithubChangedFile file = new GithubChangedFile();
        file.setFilename(filename);
        file.setStatus("modified");
        file.setPatch(patch);
        file.setAdditions(1);
        file.setDeletions(0);
        return file;
    }

    private static AiReviewResult aiReviewResult() {
        AiReviewIssue issue = new AiReviewIssue();
        issue.setFilePath("src/main/java/Demo.java");
        issue.setLineNumber(2);
        issue.setIssueType("BUG_RISK");
        issue.setSeverity("HIGH");
        issue.setTitle("Potential bug");
        issue.setDescription("Something risky changed.");
        issue.setSuggestion("Add a guard.");

        AiReviewResult result = new AiReviewResult();
        result.setIssues(List.of(issue));
        return result;
    }

    private static class TestContext {

        private final GithubClient githubClient = mock(GithubClient.class);

        private final ReviewFileService reviewFileService = mock(ReviewFileService.class);

        private final ReviewIssueService reviewIssueService = mock(ReviewIssueService.class);

        private final AiReviewService aiReviewService = mock(AiReviewService.class);

        private final ReviewTaskProcessor processor = new ReviewTaskProcessor(
                githubClient,
                new ReviewFilePlanner(new ReviewProperties()),
                reviewFileService,
                reviewIssueService,
                new ReviewIssueAssembler(),
                new ReviewFileReviewer(aiReviewService, new ReviewIssueAssembler(), new ReviewContextBuilder())
        );

        private final ArgumentCaptor<AiReviewRequest> aiReviewRequestCaptor =
                ArgumentCaptor.forClass(AiReviewRequest.class);

        @SuppressWarnings("unchecked")
        private final ArgumentCaptor<Collection<ReviewFile>> reviewFilesCaptor = ArgumentCaptor.forClass(Collection.class);

        @SuppressWarnings("unchecked")
        private final ArgumentCaptor<Collection<ReviewIssue>> reviewIssuesCaptor = ArgumentCaptor.forClass(Collection.class);
    }
}
