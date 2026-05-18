package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.codepilot.module.git.client.GithubClient;
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

    @Test
    void shouldNotCallGithubClientWhenCommentDisabled() {
        ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);
        ReviewIssueService reviewIssueService = mock(ReviewIssueService.class);
        GithubClient githubClient = mock(GithubClient.class);
        GitHubCommentServiceImpl service = new GitHubCommentServiceImpl(
                reviewTaskMapper,
                reviewIssueService,
                githubClient,
                new ReviewReportFormatter("<!-- codepilot-ai-review -->"),
                false,
                "token"
        );

        service.commentReviewResult(1L);

        verify(githubClient, never()).createPullRequestComment(any(), any(), any(), any());
        verify(reviewTaskMapper, never()).selectById(any());
    }

    @Test
    void shouldNotThrowWhenGithubClientFails() {
        ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);
        ReviewIssueService reviewIssueService = mock(ReviewIssueService.class);
        GithubClient githubClient = mock(GithubClient.class);
        GitHubCommentServiceImpl service = new GitHubCommentServiceImpl(
                reviewTaskMapper,
                reviewIssueService,
                githubClient,
                new ReviewReportFormatter("<!-- codepilot-ai-review -->"),
                true,
                "token"
        );
        when(reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(reviewIssueService.list(any(Wrapper.class))).thenReturn(List.of());
        doThrow(new RuntimeException("github error"))
                .when(githubClient)
                .createPullRequestComment(any(), any(), any(), any());

        assertThatCode(() -> service.commentReviewResult(1L)).doesNotThrowAnyException();
    }

    @Test
    void shouldCreatePassCommentWhenIssuesAreEmpty() {
        ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);
        ReviewIssueService reviewIssueService = mock(ReviewIssueService.class);
        GithubClient githubClient = mock(GithubClient.class);
        GitHubCommentServiceImpl service = new GitHubCommentServiceImpl(
                reviewTaskMapper,
                reviewIssueService,
                githubClient,
                new ReviewReportFormatter("<!-- codepilot-ai-review -->"),
                true,
                "token"
        );
        when(reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(reviewIssueService.list(any(Wrapper.class))).thenReturn(List.of());
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        service.commentReviewResult(1L);

        verify(githubClient).createPullRequestComment(eq("liche719"), eq("codeAireview"), eq(123), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("<!-- codepilot-ai-review -->");
        assertThat(bodyCaptor.getValue()).contains("No issues found");
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
}
