package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubIssueComment;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.report.ReviewReportFormatter;
import com.codepilot.module.review.service.ReviewIssueService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubCommentServiceImplTest {

    private static final String COMMENT_MARKER = "<!-- codepilot-ai-review:liche719/codeAireview -->";

    @Test
    void shouldNotCallGithubClientWhenCommentDisabled() {
        TestContext context = new TestContext(false, "token");

        context.service.commentReviewResult(1L);

        verify(context.githubClient, never()).createPullRequestComment(any(), any(), any(), any());
        verify(context.githubClient, never()).listPullRequestComments(any(), any(), any());
        verify(context.githubClient, never()).updateIssueComment(any(), any(), any(), any());
        verify(context.reviewTaskMapper, never()).selectById(any());
        verify(context.reviewIssueService, never()).list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any());
    }

    @Test
    void shouldNotCallGithubClientWhenTokenMissing() {
        TestContext context = new TestContext(true, "");

        context.service.commentReviewResult(1L);

        verify(context.githubClient, never()).createPullRequestComment(any(), any(), any(), any());
        verify(context.githubClient, never()).listPullRequestComments(any(), any(), any());
        verify(context.githubClient, never()).updateIssueComment(any(), any(), any(), any());
        verify(context.reviewTaskMapper, never()).selectById(any());
        verify(context.reviewIssueService, never()).list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any());
    }

    @Test
    void shouldCreateNewCommentWhenMarkerDoesNotExist() {
        TestContext context = new TestContext(true, "token");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any())).thenReturn(List.of());
        when(context.githubClient.listPullRequestComments("liche719", "codeAireview", 123)).thenReturn(List.of());
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        context.service.commentReviewResult(1L);

        verify(context.githubClient).createPullRequestComment(eq("liche719"), eq("codeAireview"), eq(123), bodyCaptor.capture());
        verify(context.githubClient, never()).updateIssueComment(any(), any(), any(), any());
        assertThat(bodyCaptor.getValue()).contains(COMMENT_MARKER);
        assertThat(bodyCaptor.getValue()).contains("No issues found");
    }

    @Test
    void shouldUpdateExistingCommentWhenMarkerExists() {
        TestContext context = new TestContext(true, "token");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any())).thenReturn(List.of());
        when(context.githubClient.listPullRequestComments("liche719", "codeAireview", 123))
                .thenReturn(List.of(issueComment(99L, COMMENT_MARKER + "\nold body")));
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        context.service.commentReviewResult(1L);

        verify(context.githubClient, never()).createPullRequestComment(any(), any(), any(), any());
        verify(context.githubClient).updateIssueComment(eq("liche719"), eq("codeAireview"), eq(99L), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains(COMMENT_MARKER);
        assertThat(bodyCaptor.getValue()).contains("No issues found");
    }

    @Test
    void shouldNotThrowWhenCreateCommentFails() {
        TestContext context = new TestContext(true, "token");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any())).thenReturn(List.of());
        when(context.githubClient.listPullRequestComments("liche719", "codeAireview", 123)).thenReturn(List.of());
        doThrow(new RuntimeException("github create error"))
                .when(context.githubClient)
                .createPullRequestComment(eq("liche719"), eq("codeAireview"), eq(123), any());

        assertThatCode(() -> context.service.commentReviewResult(1L)).doesNotThrowAnyException();
    }

    private GithubIssueComment issueComment(Long id, String body) {
        GithubIssueComment comment = new GithubIssueComment();
        comment.setId(id);
        comment.setBody(body);
        return comment;
    }

    private ReviewTask reviewTask() {
        ReviewTask task = new ReviewTask();
        task.setId(1L);
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(123);
        task.setRiskLevel("PASS");
        task.setTotalIssues(0);
        return task;
    }

    private static class TestContext {

        private final ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);

        private final ReviewIssueService reviewIssueService = mock(ReviewIssueService.class);

        private final GithubClient githubClient = mock(GithubClient.class);

        private final GitHubCommentServiceImpl service;

        private TestContext(boolean commentEnabled, String githubToken) {
            this.service = new GitHubCommentServiceImpl(
                    reviewTaskMapper,
                    reviewIssueService,
                    githubClient,
                    new ReviewReportFormatter(COMMENT_MARKER),
                    commentEnabled,
                    githubToken,
                    COMMENT_MARKER
            );
        }
    }
}
