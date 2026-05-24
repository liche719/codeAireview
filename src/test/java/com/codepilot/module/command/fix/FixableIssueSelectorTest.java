package com.codepilot.module.command.fix;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.agent.dto.AiReviewRequest;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.service.ReviewIssueService;
import com.codepilot.module.review.service.ReviewTaskService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixableIssueSelectorTest {

    @Test
    void shouldRequireSameHeadShaBeforeReusingReviewTaskForFix() {
        FixableIssueSelector selector = new TestContext().selector;
        PrCommandTask commandTask = task();
        commandTask.setHeadSha("abc123");
        ReviewTask reviewTask = new ReviewTask();
        reviewTask.setHeadSha("ABC123");

        assertThat(selector.hasSameHeadSha(commandTask, reviewTask)).isTrue();

        reviewTask.setHeadSha("def456");
        assertThat(selector.hasSameHeadSha(commandTask, reviewTask)).isFalse();

        reviewTask.setHeadSha(null);
        assertThat(selector.hasSameHeadSha(commandTask, reviewTask)).isFalse();
    }

    @Test
    void shouldSelectFixableIssuesFromMatchingReviewTask() {
        TestContext context = new TestContext();
        ReviewTask reviewTask = new ReviewTask();
        reviewTask.setId(10L);
        reviewTask.setHeadSha("head-sha");
        when(context.reviewTaskService.getOne(any())).thenReturn(reviewTask);
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(
                        issue("src/main/java/A.java", "BUG_RISK"),
                        issue("src/main/java/B.java", "STYLE"),
                        issue("src/main/java/C.java", "SECURITY")
                ));

        List<ReviewIssue> selected = context.selector.select(task());

        assertThat(selected)
                .extracting(ReviewIssue::getFilePath)
                .containsExactly("src/main/java/A.java", "src/main/java/C.java");
        verify(context.aiReviewService, never()).reviewFile(any());
    }

    @Test
    void shouldLimitSelectedIssuesByConfiguredFileCount() {
        TestContext context = new TestContext();
        context.properties.setFixMaxFiles(1);
        ReviewTask reviewTask = new ReviewTask();
        reviewTask.setId(10L);
        reviewTask.setHeadSha("head-sha");
        when(context.reviewTaskService.getOne(any())).thenReturn(reviewTask);
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(
                        issue("src/main/java/A.java", "BUG_RISK"),
                        issue("src/main/java/B.java", "SECURITY"),
                        issue("src/main/java/A.java", "BUG_RISK")
                ));

        List<ReviewIssue> selected = context.selector.select(task());

        assertThat(selected)
                .extracting(ReviewIssue::getFilePath)
                .containsExactly("src/main/java/A.java", "src/main/java/A.java");
    }

    @Test
    void shouldRunAdHocReviewWhenNoSuccessfulReviewTaskExists() {
        TestContext context = new TestContext();
        when(context.reviewTaskService.getOne(any())).thenReturn(null);
        when(context.githubClient.listPullRequestFiles("liche719", "codeAireview", 12))
                .thenReturn(List.of(changedFile("src/main/java/Demo.java")));
        AiReviewIssue aiIssue = new AiReviewIssue();
        aiIssue.setLineNumber(3);
        aiIssue.setIssueType("BUG_RISK");
        aiIssue.setTitle("Demo bug");
        AiReviewResult result = new AiReviewResult();
        result.setIssues(List.of(aiIssue));
        when(context.aiReviewService.reviewFile(any(AiReviewRequest.class))).thenReturn(result);

        List<ReviewIssue> selected = context.selector.select(task());

        assertThat(selected).hasSize(1);
        assertThat(selected.getFirst().getFilePath()).isEqualTo("src/main/java/Demo.java");
        verify(context.commandTaskLogService).record(eq(1L), eq("CONTEXT"), eq(true),
                eq("未找到成功的审查任务，改为执行一次临时审查。"), any());
    }

    private static PrCommandTask task() {
        PrCommandTask task = new PrCommandTask();
        task.setId(1L);
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(12);
        task.setHeadSha("head-sha");
        return task;
    }

    private static ReviewIssue issue(String filePath, String issueType) {
        ReviewIssue issue = new ReviewIssue();
        issue.setFilePath(filePath);
        issue.setLineNumber(1);
        issue.setIssueType(issueType);
        return issue;
    }

    private static GithubChangedFile changedFile(String filePath) {
        GithubChangedFile file = new GithubChangedFile();
        file.setFilename(filePath);
        file.setPatch("""
                @@ -1 +1,2 @@
                 class Demo {
                +  void run() {}
                """);
        return file;
    }

    private static class TestContext {

        private final GithubCommandProperties properties = new GithubCommandProperties();

        private final GithubClient githubClient = mock(GithubClient.class);

        private final ReviewTaskService reviewTaskService = mock(ReviewTaskService.class);

        private final ReviewIssueService reviewIssueService = mock(ReviewIssueService.class);

        private final AiReviewService aiReviewService = mock(AiReviewService.class);

        private final PrCommandTaskLogService commandTaskLogService = mock(PrCommandTaskLogService.class);

        private final FixableIssueSelector selector = new FixableIssueSelector(
                properties,
                githubClient,
                reviewTaskService,
                reviewIssueService,
                aiReviewService,
                commandTaskLogService
        );
    }
}
