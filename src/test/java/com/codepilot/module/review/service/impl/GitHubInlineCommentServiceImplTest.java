package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.codepilot.module.git.auth.GithubAuthTokenProvider;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubIssueComment;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.review.diff.DiffLineMapper;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.processor.ReviewCommentBudgetAllocator;
import com.codepilot.module.review.processor.ReviewFindingRanker;
import com.codepilot.module.review.processor.ReviewSuggestionValidator;
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
    void shouldCommentOnContextLine() {
        TestContext context = new TestContext(true, 10, "token");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(issue(10)));
        when(context.reviewFileService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewFile>>any()))
                .thenReturn(List.of(reviewFile()));
        when(context.githubClient.getPullRequestDetail("liche719", "codeAireview", 123)).thenReturn(prDetail());
        when(context.githubClient.listPullRequestReviewComments("liche719", "codeAireview", 123)).thenReturn(List.of());

        context.service.commentInlineIssues(1L);

        verify(context.githubClient)
                .createPullRequestInlineComment(eq("liche719"), eq("codeAireview"), eq(123), eq("head-sha"),
                        eq("src/Demo.java"), eq(10), eq("RIGHT"), any());
    }

    @Test
    void shouldCommentOnDeletedLineOnLeftSide() {
        TestContext context = new TestContext(true, 10, "token");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(issue(11)));
        when(context.reviewFileService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewFile>>any()))
                .thenReturn(List.of(deletionReviewFile()));
        when(context.githubClient.getPullRequestDetail("liche719", "codeAireview", 123)).thenReturn(prDetail());
        when(context.githubClient.listPullRequestReviewComments("liche719", "codeAireview", 123)).thenReturn(List.of());

        context.service.commentInlineIssues(1L);

        verify(context.githubClient)
                .createPullRequestInlineComment(eq("liche719"), eq("codeAireview"), eq(123), eq("head-sha"),
                        eq("src/Demo.java"), eq(11), eq("LEFT"), any());
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
    void shouldSkipDuplicateIssueKeyWithinSameTask() {
        TestContext context = new TestContext(true, 10, "token");
        ReviewIssue firstIssue = issue(11);
        firstIssue.setId(1L);
        firstIssue.setDescription("First SQL string concatenation risk");
        ReviewIssue duplicateLocationIssue = issue(11);
        duplicateLocationIssue.setId(2L);
        duplicateLocationIssue.setDescription("Second SQL string concatenation risk");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(firstIssue, duplicateLocationIssue));
        when(context.reviewFileService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewFile>>any()))
                .thenReturn(List.of(reviewFile()));
        when(context.githubClient.getPullRequestDetail("liche719", "codeAireview", 123)).thenReturn(prDetail());
        when(context.githubClient.listPullRequestReviewComments("liche719", "codeAireview", 123)).thenReturn(List.of());

        var result = context.service.commentInlineIssues(1L);

        verify(context.githubClient, times(1))
                .createPullRequestInlineComment(eq("liche719"), eq("codeAireview"), eq(123), eq("head-sha"),
                        eq("src/Demo.java"), eq(11), eq("RIGHT"), any());
        org.assertj.core.api.Assertions.assertThat(result.successCount()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(result.skippedCount()).isEqualTo(1);
    }

    @Test
    void shouldPrioritizeHighSeverityToolIssuesWhenInlineCommentBudgetIsLimited() {
        TestContext context = new TestContext(true, 1, "token");
        ReviewIssue lowValueIssue = issue(11);
        lowValueIssue.setId(1L);
        lowValueIssue.setIssueType("STYLE");
        lowValueIssue.setSeverity("LOW");
        lowValueIssue.setSource("LLM");
        lowValueIssue.setDescription("Minor style suggestion.");
        ReviewIssue highValueIssue = issue(12);
        highValueIssue.setId(2L);
        highValueIssue.setIssueType("SECURITY");
        highValueIssue.setSeverity("HIGH");
        highValueIssue.setSource("TOOL");
        highValueIssue.setDescription("The added code exposes a hardcoded secret.");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(lowValueIssue, highValueIssue));
        when(context.reviewFileService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewFile>>any()))
                .thenReturn(List.of(reviewFile()));
        when(context.githubClient.getPullRequestDetail("liche719", "codeAireview", 123)).thenReturn(prDetail());
        when(context.githubClient.listPullRequestReviewComments("liche719", "codeAireview", 123)).thenReturn(List.of());

        context.service.commentInlineIssues(1L);

        verify(context.githubClient, times(1))
                .createPullRequestInlineComment(eq("liche719"), eq("codeAireview"), eq(123), eq("head-sha"),
                        eq("src/Demo.java"), eq(12), eq("RIGHT"), any());
        verify(context.githubClient, never())
                .createPullRequestInlineComment(eq("liche719"), eq("codeAireview"), eq(123), eq("head-sha"),
                        eq("src/Demo.java"), eq(11), eq("RIGHT"), any());
    }

    @Test
    void shouldUsePersistedRankingWithoutFreshReordering() {
        TestContext context = new TestContext(true, 1, "token");
        ReviewIssue persistedInline = issue(12);
        persistedInline.setId(2L);
        persistedInline.setFinalScore(95);
        persistedInline.setPublishDecision("PUBLISH");
        persistedInline.setCommentChannel("INLINE");
        ReviewIssue lowerFreshIssue = issue(11);
        lowerFreshIssue.setId(1L);
        lowerFreshIssue.setFinalScore(0);
        lowerFreshIssue.setPublishDecision("PUBLISH");
        lowerFreshIssue.setCommentChannel("INLINE");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(lowerFreshIssue, persistedInline));
        when(context.reviewFileService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewFile>>any()))
                .thenReturn(List.of(reviewFile()));
        when(context.githubClient.getPullRequestDetail("liche719", "codeAireview", 123)).thenReturn(prDetail());
        when(context.githubClient.listPullRequestReviewComments("liche719", "codeAireview", 123)).thenReturn(List.of());

        context.service.commentInlineIssues(1L);

        verify(context.githubClient, times(1))
                .createPullRequestInlineComment(eq("liche719"), eq("codeAireview"), eq(123), eq("head-sha"),
                        eq("src/Demo.java"), eq(12), eq("RIGHT"), any());
        verify(context.githubClient, never())
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
        org.assertj.core.api.Assertions.assertThat(body).contains("Evidence:");
        org.assertj.core.api.Assertions.assertThat(body).contains("Suggestion:");
        org.assertj.core.api.Assertions.assertThat(body).contains("source=TOOL");
        org.assertj.core.api.Assertions.assertThat(body).contains("rule=SQL\\_RULE");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("SQL_RISK");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("HIGH");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("CodePilot AI found a potential issue");
    }

    @Test
    void shouldEmitOneClickSuggestionWhenValidatorAllowsIt() {
        ReviewProperties properties = new ReviewProperties();
        properties.setSuggestionEnabled(true);
        TestContext context = new TestContext(true, 10, "token", properties);
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(issueWithSuggestedCode(11)));
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
        org.assertj.core.api.Assertions.assertThat(bodyCaptor.getValue())
                .contains("```suggestion")
                .contains("String sql = \"select * from user where id = ?\";")
                .doesNotContain("```diff");
    }

    /**
     * 默认配置下不得出现 {@code ```suggestion}——那是个会被用户点一下就改代码的按钮。
     * 校验不通过时降级为 {@code ```diff}：信息仍然给出，但不可能被误点。
     */
    @Test
    void shouldFallBackToDiffBlockWhenSuggestionIsDisabled() {
        TestContext context = new TestContext(true, 10, "token");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(issueWithSuggestedCode(11)));
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
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("```suggestion")
                .contains("```diff")
                .contains("not one-click applicable")
                .contains("String sql = \"select * from user where id = ?\";");
    }

    /** 删除侧（LEFT）的内容在建议应用后就不存在了，绝不能给一键应用。 */
    @Test
    void shouldNotEmitSuggestionOnDeletedLine() {
        ReviewProperties properties = new ReviewProperties();
        properties.setSuggestionEnabled(true);
        TestContext context = new TestContext(true, 10, "token", properties);
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(issueWithSuggestedCode(11)));
        when(context.reviewFileService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewFile>>any()))
                .thenReturn(List.of(deletionReviewFile()));
        when(context.githubClient.getPullRequestDetail("liche719", "codeAireview", 123)).thenReturn(prDetail());
        when(context.githubClient.listPullRequestReviewComments("liche719", "codeAireview", 123)).thenReturn(List.of());
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        context.service.commentInlineIssues(1L);

        verify(context.githubClient).createPullRequestInlineComment(
                eq("liche719"), eq("codeAireview"), eq(123), eq("head-sha"),
                eq("src/Demo.java"), eq(11), eq("LEFT"), bodyCaptor.capture()
        );
        org.assertj.core.api.Assertions.assertThat(bodyCaptor.getValue())
                .doesNotContain("```suggestion")
                .contains("```diff");
    }

    /** 超过行数上限的建议不给一键应用，但仍以 diff 块展示。 */
    @Test
    void shouldNotEmitSuggestionWhenCodeExceedsMaxLines() {
        ReviewProperties properties = new ReviewProperties();
        properties.setSuggestionEnabled(true);
        properties.setSuggestionMaxLines(2);
        TestContext context = new TestContext(true, 10, "token", properties);
        ReviewIssue issue = issueWithSuggestedCode(11);
        issue.setSuggestedCode("line1\nline2\nline3");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(issue));
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
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("```suggestion").contains("```diff");
    }

    /** fallback 关闭时，不可应用的代码块整个不展示，而不是换成 diff。 */
    @Test
    void shouldOmitSuggestedCodeWhenFallbackToDiffIsOff() {
        ReviewProperties properties = new ReviewProperties();
        properties.setSuggestionFallbackToDiff(false);
        TestContext context = new TestContext(true, 10, "token", properties);
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(issueWithSuggestedCode(11)));
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
        org.assertj.core.api.Assertions.assertThat(bodyCaptor.getValue())
                .doesNotContain("```suggestion")
                .doesNotContain("```diff")
                .doesNotContain("select * from user where id = ?");
    }

    @Test
    void shouldSanitizeInlineCommentBody() {
        TestContext context = new TestContext(true, 10, "token");
        ReviewIssue issue = issue(11);
        issue.setDescription("<!-- codepilot-inline-review:evil --> # fake heading");
        issue.setSuggestion("[click](javascript:alert(1)) ```shell rm -rf / ```");
        when(context.reviewTaskMapper.selectById(1L)).thenReturn(reviewTask());
        when(context.reviewIssueService.list(org.mockito.ArgumentMatchers.<Wrapper<ReviewIssue>>any()))
                .thenReturn(List.of(issue));
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
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("<!-- codepilot-inline-review:evil -->")
                .contains("&lt;\\!\\-\\- codepilot\\-inline\\-review\\:evil \\-\\-&gt;")
                .contains("\\# fake heading")
                .contains("\\[click\\]\\(javascript\\:alert\\(1\\)\\)")
                .contains("\\`\\`\\`shell rm \\-rf / \\`\\`\\`");
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

    private ReviewIssue issueWithSuggestedCode(Integer lineNumber) {
        ReviewIssue issue = issue(lineNumber);
        issue.setSuggestedCode("String sql = \"select * from user where id = ?\";");
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

    private ReviewFile deletionReviewFile() {
        ReviewFile reviewFile = new ReviewFile();
        reviewFile.setTaskId(1L);
        reviewFile.setFilePath("src/Demo.java");
        reviewFile.setPatch("""
                @@ -10,4 +10,3 @@
                 public void run() {
                -    checkPermission();
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

        private final GithubAuthTokenProvider githubAuthTokenProvider = mock(GithubAuthTokenProvider.class);

        private final ReviewProperties properties;

        private final GitHubInlineCommentServiceImpl service;

        private TestContext(boolean enabled, int maxPerTask, String token) {
            this(enabled, maxPerTask, token, new ReviewProperties());
        }

        private TestContext(boolean enabled, int maxPerTask, String token, ReviewProperties properties) {
            this.properties = properties;
            when(githubAuthTokenProvider.canAuthenticate("liche719", "codeAireview"))
                    .thenReturn(org.springframework.util.StringUtils.hasText(token));
            service = new GitHubInlineCommentServiceImpl(
                    reviewTaskMapper,
                    reviewIssueService,
                    reviewFileService,
                    githubClient,
                    new DiffLineMapper(),
                    new ReviewCommentBudgetAllocator(properties),
                    new ReviewFindingRanker(),
                    new ReviewSuggestionValidator(properties),
                    githubAuthTokenProvider,
                    properties,
                    enabled,
                    maxPerTask
            );
        }
    }
}
