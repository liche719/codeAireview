package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.codepilot.module.git.auth.GithubAuthTokenProvider;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubIssueComment;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.processor.ReviewCommentBudgetAllocator;
import com.codepilot.module.review.processor.ReviewFindingRanker;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.report.CommentMarkers;
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
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());

        context.service.commentReviewResult(1L);

        verify(context.githubClient, never()).createPullRequestComment(any(), any(), any(), any());
        verify(context.githubClient, never()).listPullRequestComments(any(), any(), any());
        verify(context.githubClient, never()).updateIssueComment(any(), any(), any(), any());
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
        assertThat(bodyCaptor.getValue()).contains("未发现问题");
    }

    @Test
    void shouldUpdateExistingCommentWhenMarkerExists() {
        TestContext context = new TestContext(true, "token");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any())).thenReturn(List.of());
        when(context.githubClient.listPullRequestComments("liche719", "codeAireview", 123))
                .thenReturn(List.of(githubComment(1001L, COMMENT_MARKER + "\nold body", "2026-01-01T00:00:00Z")));
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        context.service.commentReviewResult(1L);

        verify(context.githubClient, never()).createPullRequestComment(any(), any(), any(), any());
        verify(context.githubClient).updateIssueComment(eq("liche719"), eq("codeAireview"), eq(1001L), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains(COMMENT_MARKER);
        assertThat(bodyCaptor.getValue()).contains("未发现问题");
    }

    /**
     * S1 回归测试：Agent 回复与审查摘要同时存在时，摘要更新<b>不得</b>命中 Agent 回复。
     *
     * <p>这是修复前必现的缺陷——两类评论共用同一个身份标记，且匹配时没有作者/类型过滤，
     * 于是"最近更新的那条"会被覆盖，静默丢失用户的 Agent 回复。</p>
     */
    @Test
    void shouldNotOverwriteAgentReplyWhenSummaryIsUpdated() {
        TestContext context = new TestContext(true, "token");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any())).thenReturn(List.of());
        when(context.githubClient.getAuthenticatedUserLogin()).thenReturn("codepilot-bot[bot]");
        when(context.githubClient.listPullRequestComments("liche719", "codeAireview", 123))
                .thenReturn(List.of(
                        githubComment(1001L, COMMENT_MARKER + "\n既往摘要", "2026-01-01T00:00:00Z", "codepilot-bot[bot]"),
                        // Agent 回复更新，且由同一个机器人发出——修复前它会被摘要覆盖
                        githubComment(2002L, CommentMarkers.AGENT_REPLY + "\n用户问题的回答", "2026-06-01T00:00:00Z", "codepilot-bot[bot]")
                ));
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        context.service.commentReviewResult(1L);

        verify(context.githubClient, never()).createPullRequestComment(any(), any(), any(), any());
        // 必须更新摘要（1001），而不是更新的 Agent 回复（2002）
        verify(context.githubClient).updateIssueComment(eq("liche719"), eq("codeAireview"), eq(1001L), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains(COMMENT_MARKER);
    }

    /**
     * 身份标记只在前 N 行内匹配：正文里"引用"到标记不应被当成本机器人的摘要。
     */
    @Test
    void shouldIgnoreMarkerThatAppearsDeepInsideAnotherComment() {
        TestContext context = new TestContext(true, "token");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any())).thenReturn(List.of());
        when(context.githubClient.getAuthenticatedUserLogin()).thenReturn("codepilot-bot[bot]");
        String deepBody = """
                这是一条人工评论。

                下面引用机器人的标记用于讨论：
                %s
                以上。
                """.formatted(COMMENT_MARKER);
        when(context.githubClient.listPullRequestComments("liche719", "codeAireview", 123))
                .thenReturn(List.of(githubComment(3003L, deepBody, "2026-06-01T00:00:00Z", "human-user")));

        context.service.commentReviewResult(1L);

        verify(context.githubClient, never()).updateIssueComment(any(), any(), any(), any());
        verify(context.githubClient).createPullRequestComment(eq("liche719"), eq("codeAireview"), eq(123), any());
    }

    /**
     * 标记正确但作者不是本机器人时不得更新——防止误改他人评论。
     */
    @Test
    void shouldNotUpdateCommentWithMatchingMarkerFromAnotherAuthor() {
        TestContext context = new TestContext(true, "token");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any())).thenReturn(List.of());
        when(context.githubClient.getAuthenticatedUserLogin()).thenReturn("codepilot-bot[bot]");
        when(context.githubClient.listPullRequestComments("liche719", "codeAireview", 123))
                .thenReturn(List.of(githubComment(4004L, COMMENT_MARKER + "\n人工粘贴的标记", "2026-06-01T00:00:00Z", "human-user")));

        context.service.commentReviewResult(1L);

        verify(context.githubClient, never()).updateIssueComment(any(), any(), any(), any());
        verify(context.githubClient).createPullRequestComment(eq("liche719"), eq("codeAireview"), eq(123), any());
    }

    /**
     * 机器人登录名未知时降级为只按标记匹配，而不是拒绝更新——
     * 此时身份标记的分离已保证不会误命中其它用途的评论。
     */
    @Test
    void shouldFallBackToMarkerOnlyMatchWhenBotLoginUnavailable() {
        TestContext context = new TestContext(true, "token");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any())).thenReturn(List.of());
        when(context.githubClient.getAuthenticatedUserLogin()).thenReturn(null);
        when(context.githubClient.listPullRequestComments("liche719", "codeAireview", 123))
                .thenReturn(List.of(githubComment(5005L, COMMENT_MARKER + "\nold body", "2026-01-01T00:00:00Z", null)));

        context.service.commentReviewResult(1L);

        verify(context.githubClient).updateIssueComment(eq("liche719"), eq("codeAireview"), eq(5005L), any());
    }

    @Test
    void shouldUpdateLatestExistingCommentWhenMultipleMarkersExist() {
        TestContext context = new TestContext(true, "token");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any())).thenReturn(List.of());
        when(context.githubClient.listPullRequestComments("liche719", "codeAireview", 123))
                .thenReturn(List.of(
                        githubComment(1001L, COMMENT_MARKER + "\nold body", "2026-01-01T00:00:00Z"),
                        githubComment(1002L, COMMENT_MARKER + "\nnew body", "2026-01-02T00:00:00Z")
                ));

        context.service.commentReviewResult(1L);

        verify(context.githubClient).updateIssueComment(eq("liche719"), eq("codeAireview"), eq(1002L), any());
        verify(context.githubClient, never()).createPullRequestComment(any(), any(), any(), any());
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

    private GithubIssueComment githubComment(Long id, String body, String updatedAt) {
        GithubIssueComment comment = new GithubIssueComment();
        comment.setId(id);
        comment.setBody(body);
        comment.setUpdatedAt(updatedAt);
        return comment;
    }

    private GithubIssueComment githubComment(Long id, String body, String updatedAt, String userLogin) {
        GithubIssueComment comment = githubComment(id, body, updatedAt);
        comment.setUserLogin(userLogin);
        return comment;
    }

    private static class TestContext {

        private final ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);

        private final ReviewIssueService reviewIssueService = mock(ReviewIssueService.class);

        private final GithubClient githubClient = mock(GithubClient.class);

        private final GithubAuthTokenProvider githubAuthTokenProvider = mock(GithubAuthTokenProvider.class);

        private final GitHubCommentServiceImpl service;

        private TestContext(boolean commentEnabled, String githubToken) {
            when(githubAuthTokenProvider.canAuthenticate("liche719", "codeAireview"))
                    .thenReturn(org.springframework.util.StringUtils.hasText(githubToken));
            this.service = new GitHubCommentServiceImpl(
                    reviewTaskMapper,
                    reviewIssueService,
                    githubClient,
                    new ReviewReportFormatter(
                            COMMENT_MARKER,
                            new ReviewCommentBudgetAllocator(new ReviewProperties()),
                            new ReviewFindingRanker()
                    ),
                    githubAuthTokenProvider,
                    commentEnabled,
                    CommentMarkers.DEFAULT_HEADER_LINES
            );
        }
    }
}
