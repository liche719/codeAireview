package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubIssueComment;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.review.diff.DiffLineMapper;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.service.ReviewFileService;
import com.codepilot.module.review.service.ReviewIssueService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubInlineCommentServiceImplTest {

    @Test
    void shouldNotCallGithubClientWhenInlineCommentDisabled() {
        TestContext context = new TestContext(false, 10, "token");

        context.service.commentInlineIssues(1L);

        verify(context.githubClient, never()).getPullRequestDetail(any(), any(), any());
        verify(context.githubClient, never()).createPullRequestInlineComment(any(), any(), any(), any(), any(), any(), any(), any());
        verify(context.reviewTaskMapper, never()).selectById(any());
    }

    @Test
    void shouldSkipIssueWhenLineNumberIsMissing() {
        TestContext context = new TestContext(true, 10, "token");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(issue(null)));
        when(context.reviewFileService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewFile>>any()))
                .thenReturn(List.of(reviewFile()));
        when(context.githubClient.getPullRequestDetail("liche719", "codeAireview", 123)).thenReturn(prDetail());
        when(context.githubClient.listPullRequestReviewComments("liche719", "codeAireview", 123)).thenReturn(List.of());

        context.service.commentInlineIssues(1L);

        verify(context.githubClient, never()).createPullRequestInlineComment(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldSkipIssueWhenLineIsNotAddedLine() {
        TestContext context = new TestContext(true, 10, "token");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(issue(10)));
        when(context.reviewFileService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewFile>>any()))
                .thenReturn(List.of(reviewFile()));
        when(context.githubClient.getPullRequestDetail("liche719", "codeAireview", 123)).thenReturn(prDetail());
        when(context.githubClient.listPullRequestReviewComments("liche719", "codeAireview", 123)).thenReturn(List.of());

        context.service.commentInlineIssues(1L);

        verify(context.githubClient, never()).createPullRequestInlineComment(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldNotThrowWhenGithubClientFails() {
        TestContext context = new TestContext(true, 10, "token");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(issue(11)));
        when(context.reviewFileService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewFile>>any()))
                .thenReturn(List.of(reviewFile()));
        when(context.githubClient.getPullRequestDetail("liche719", "codeAireview", 123)).thenReturn(prDetail());
        when(context.githubClient.listPullRequestReviewComments("liche719", "codeAireview", 123)).thenReturn(List.of());
        doThrow(new RuntimeException("github inline error"))
                .when(context.githubClient)
                .createPullRequestInlineComment(eq("liche719"), eq("codeAireview"), eq(123), eq("head-sha"),
                        eq("src/Demo.java"), eq(11), eq("RIGHT"), any());

        assertThatCode(() -> context.service.commentInlineIssues(1L)).doesNotThrowAnyException();
    }

    @Test
    void shouldRespectMaxInlineCommentsPerTask() {
        TestContext context = new TestContext(true, 1, "token");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(issue(11), issue(12)));
        when(context.reviewFileService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewFile>>any()))
                .thenReturn(List.of(reviewFile()));
        when(context.githubClient.getPullRequestDetail("liche719", "codeAireview", 123)).thenReturn(prDetail());
        when(context.githubClient.listPullRequestReviewComments("liche719", "codeAireview", 123)).thenReturn(List.of());

        context.service.commentInlineIssues(1L);

        verify(context.githubClient, times(1))
                .createPullRequestInlineComment(eq("liche719"), eq("codeAireview"), eq(123), eq("head-sha"),
                        eq("src/Demo.java"), eq(11), eq("RIGHT"), any());
    }

    @Test
    void shouldBuildConciseInlineCommentBody() {
        TestContext context = new TestContext(true, 10, "token");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(issue(11)));
        when(context.reviewFileService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewFile>>any()))
                .thenReturn(List.of(reviewFile()));
        when(context.githubClient.getPullRequestDetail("liche719", "codeAireview", 123)).thenReturn(prDetail());
        when(context.githubClient.listPullRequestReviewComments("liche719", "codeAireview", 123)).thenReturn(List.of());
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        context.service.commentInlineIssues(1L);

        verify(context.githubClient).createPullRequestInlineComment(
                eq("liche719"), eq("codeAireview"), eq(123), eq("head-sha"),
                eq("src/Demo.java"), eq(11), eq("RIGHT"), bodyCaptor.capture()
        );
        String body = bodyCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(body).contains("<!-- codepilot-inline-review -->");
        org.assertj.core.api.Assertions.assertThat(body).containsPattern("<!-- codepilot-inline-review:[a-f0-9]{32} -->");
        org.assertj.core.api.Assertions.assertThat(body).contains("Description:");
        org.assertj.core.api.Assertions.assertThat(body).contains("Suggestion:");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("SQL_RISK");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("HIGH");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("Source:");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("Rule:");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("CodePilot AI found a potential issue");
    }

    @Test
    void shouldSkipIssueWhenExistingInlineFingerprintWasAlreadyPosted() {
        TestContext context = new TestContext(true, 10, "token");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(issue(11)));
        when(context.reviewFileService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewFile>>any()))
                .thenReturn(List.of(reviewFile()));
        when(context.githubClient.getPullRequestDetail("liche719", "codeAireview", 123)).thenReturn(prDetail());
        GithubIssueComment existingComment = new GithubIssueComment();
        existingComment.setBody("""
                <!-- codepilot-inline-review -->

                <!-- codepilot-inline-review:9317972e05251538a296263a54a11f8b -->
                """);
        when(context.githubClient.listPullRequestReviewComments("liche719", "codeAireview", 123))
                .thenReturn(List.of(existingComment));

        context.service.commentInlineIssues(1L);

        verify(context.githubClient, never())
                .createPullRequestInlineComment(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private ReviewTask reviewTask() {
        ReviewTask task = new ReviewTask();
        task.setId(1L);
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(123);
        return task;
    }

    private ReviewIssue issue(Integer lineNumber) {
        ReviewIssue issue = new ReviewIssue();
        issue.setTaskId(1L);
        issue.setFilePath("src/Demo.java");
        issue.setLineNumber(lineNumber);
        issue.setIssueType("SQL_RISK");
        issue.setSeverity("HIGH");
        issue.setSource("TOOL");
        issue.setRuleReference("SQL_RULE");
        issue.setDescription("SQL string concatenation risk");
        issue.setSuggestion("Use prepared statement");
        return issue;
    }

    private ReviewFile reviewFile() {
        ReviewFile reviewFile = new ReviewFile();
        reviewFile.setTaskId(1L);
        reviewFile.setFilePath("src/Demo.java");
        reviewFile.setPatch("""
                @@ -10,4 +10,6 @@
                 public void run() {
                +    String sql = "select * from user where name = '" + name + "'";
                +    log.info(sql);
                     System.out.println(sql);
                 }
                """);
        return reviewFile;
    }

    private GithubPullRequestDetail prDetail() {
        GithubPullRequestDetail detail = new GithubPullRequestDetail();
        detail.setNumber(123);
        detail.setHeadSha("head-sha");
        return detail;
    }

    private static class TestContext {

        private final ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);

        private final ReviewIssueService reviewIssueService = mock(ReviewIssueService.class);

        private final ReviewFileService reviewFileService = mock(ReviewFileService.class);

        private final GithubClient githubClient = mock(GithubClient.class);

        private final GitHubInlineCommentServiceImpl service;

        private TestContext(boolean enabled, int maxPerTask, String token) {
            service = new GitHubInlineCommentServiceImpl(
                    reviewTaskMapper,
                    reviewIssueService,
                    reviewFileService,
                    githubClient,
                    new DiffLineMapper(),
                    enabled,
                    maxPerTask,
                    token
            );
        }
    }
}
