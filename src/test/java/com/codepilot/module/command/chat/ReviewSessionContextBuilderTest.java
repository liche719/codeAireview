package com.codepilot.module.command.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.processor.ReviewFindingRanker;
import com.codepilot.module.review.service.ReviewIssueService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReviewSessionContextBuilderTest {

    @Test
    void shouldBuildBoundedContextFromLatestSuccessfulReviewTask() {
        ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);
        ReviewIssueService reviewIssueService = mock(ReviewIssueService.class);
        GithubClient githubClient = mock(GithubClient.class);
        ReviewSessionContextBuilder builder = new ReviewSessionContextBuilder(
                reviewTaskMapper,
                reviewIssueService,
                new ReviewFindingRanker(),
                githubClient
        );
        String secret = "ghp_" + "123456789012345678901234567890123456";
        ReviewTask task = successfulTask();
        ReviewIssue high = issue(
                1L,
                "src/main/java/App.java",
                42,
                "HIGH",
                "SECURITY",
                "SQL injection risk",
                "User input reaches SQL builder with " + "token=" + secret,
                "Use parameter binding.",
                "TOOL",
                "SQL_RISK|PATCH_VERIFIED:PATCH_LINE",
                120,
                "PUBLISH",
                "INLINE",
                null
        );
        ReviewIssue suppressed = issue(
                2L,
                "src/main/resources/application.yml",
                12,
                "LOW",
                "SQL_RISK",
                "Spring placeholder looks like SQL interpolation",
                "This was detected from a configuration placeholder.",
                "No action needed.",
                "TOOL",
                "SQL_RISK",
                15,
                "SUPPRESS",
                "NONE",
                "below publish score threshold"
        );

        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(task));
        when(reviewIssueService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(suppressed, high));
        when(githubClient.getPullRequestDetail("liche719", "codeAireview", 7))
                .thenReturn(prDetail("abcdef1234567890abcdef"));

        String context = builder.build("liche719", "codeAireview", 7);

        assertThat(context)
                .contains("Latest stored PR review session:")
                .contains("- taskId: 99")
                .contains("- pr: liche719/codeAireview#7")
                .contains("- reviewedHeadSha: abcdef123456")
                .contains("- currentHeadSha: abcdef123456")
                .contains("- reviewFreshness: FRESH")
                .contains("publishableFindings: 1")
                .contains("suppressedFindings: 1")
                .contains("decision=PUBLISH")
                .contains("location=src/main/java/App.java:42")
                .contains("evidence: source=TOOL, rule=SQL_RISK, grounding=changed diff line")
                .contains("[REDACTED]")
                .doesNotContain(secret);
        assertThat(context.length()).isLessThanOrEqualTo(6000);
    }

    @Test
    void shouldExplainWhenNoSuccessfulReviewExists() {
        ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);
        ReviewIssueService reviewIssueService = mock(ReviewIssueService.class);
        GithubClient githubClient = mock(GithubClient.class);
        ReviewSessionContextBuilder builder = new ReviewSessionContextBuilder(
                reviewTaskMapper,
                reviewIssueService,
                new ReviewFindingRanker(),
                githubClient
        );
        ReviewTask runningTask = successfulTask();
        runningTask.setId(100L);
        runningTask.setStatus("RUNNING");

        when(reviewTaskMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(runningTask));

        String context = builder.build("liche719", "codeAireview", 7);

        assertThat(context)
                .contains("No successful stored review result is available")
                .contains("latestTaskId: 100")
                .contains("latestStatus: RUNNING")
                .contains("run @x-pilotx review");
        verifyNoInteractions(reviewIssueService);
    }

    @Test
    void shouldMarkReviewContextStaleWhenCurrentPrHeadDiffers() {
        ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);
        ReviewIssueService reviewIssueService = mock(ReviewIssueService.class);
        GithubClient githubClient = mock(GithubClient.class);
        ReviewSessionContextBuilder builder = new ReviewSessionContextBuilder(
                reviewTaskMapper,
                reviewIssueService,
                new ReviewFindingRanker(),
                githubClient
        );

        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(successfulTask()));
        when(reviewIssueService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(githubClient.getPullRequestDetail("liche719", "codeAireview", 7))
                .thenReturn(prDetail("999999999999999999999999"));

        String context = builder.build("liche719", "codeAireview", 7);

        assertThat(context)
                .contains("- reviewedHeadSha: abcdef123456")
                .contains("- currentHeadSha: 999999999999")
                .contains("- reviewFreshness: STALE")
                .contains("do not present findings as current");
    }

    @Test
    void shouldKeepReviewContextUsableWhenCurrentPrHeadCannotBeFetched() {
        ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);
        ReviewIssueService reviewIssueService = mock(ReviewIssueService.class);
        GithubClient githubClient = mock(GithubClient.class);
        ReviewSessionContextBuilder builder = new ReviewSessionContextBuilder(
                reviewTaskMapper,
                reviewIssueService,
                new ReviewFindingRanker(),
                githubClient
        );

        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(successfulTask()));
        when(reviewIssueService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(githubClient.getPullRequestDetail("liche719", "codeAireview", 7))
                .thenThrow(new IllegalStateException("github unavailable"));

        String context = builder.build("liche719", "codeAireview", 7);

        assertThat(context)
                .contains("- currentHeadSha: N/A")
                .contains("- reviewFreshness: UNKNOWN")
                .contains("Latest stored PR review session:");
    }

    @Test
    void shouldReturnSafeMessageForIncompletePrIdentity() {
        ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);
        ReviewIssueService reviewIssueService = mock(ReviewIssueService.class);
        GithubClient githubClient = mock(GithubClient.class);
        ReviewSessionContextBuilder builder = new ReviewSessionContextBuilder(
                reviewTaskMapper,
                reviewIssueService,
                new ReviewFindingRanker(),
                githubClient
        );

        String context = builder.build("", "codeAireview", 7);

        assertThat(context).contains("PR identity is incomplete");
        verifyNoInteractions(reviewTaskMapper, reviewIssueService);
    }

    private GithubPullRequestDetail prDetail(String headSha) {
        GithubPullRequestDetail detail = new GithubPullRequestDetail();
        detail.setHeadSha(headSha);
        return detail;
    }

    private ReviewTask successfulTask() {
        ReviewTask task = new ReviewTask();
        task.setId(99L);
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(7);
        task.setTitle("Improve review interaction");
        task.setHeadSha("abcdef1234567890abcdef");
        task.setStatus("SUCCESS");
        task.setTotalFiles(3);
        task.setTotalIssues(2);
        task.setRiskLevel("HIGH");
        task.setFinishedAt(LocalDateTime.of(2026, 6, 1, 10, 30));
        task.setUpdatedAt(LocalDateTime.of(2026, 6, 1, 10, 31));
        return task;
    }

    private ReviewIssue issue(
            Long id,
            String filePath,
            Integer lineNumber,
            String severity,
            String issueType,
            String title,
            String description,
            String suggestion,
            String source,
            String ruleReference,
            Integer finalScore,
            String publishDecision,
            String commentChannel,
            String suppressionReason
    ) {
        ReviewIssue issue = new ReviewIssue();
        issue.setId(id);
        issue.setTaskId(99L);
        issue.setFilePath(filePath);
        issue.setLineNumber(lineNumber);
        issue.setSeverity(severity);
        issue.setIssueType(issueType);
        issue.setTitle(title);
        issue.setDescription(description);
        issue.setSuggestion(suggestion);
        issue.setSource(source);
        issue.setRuleReference(ruleReference);
        issue.setFinalScore(finalScore);
        issue.setPublishDecision(publishDecision);
        issue.setCommentChannel(commentChannel);
        issue.setSuppressionReason(suppressionReason);
        return issue;
    }
}
