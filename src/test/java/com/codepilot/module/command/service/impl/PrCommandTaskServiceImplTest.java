package com.codepilot.module.command.service.impl;

import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.failure.PrCommandTaskFailureHandler;
import com.codepilot.module.command.fix.FixableIssueSelector;
import com.codepilot.module.command.fix.FixPatchScopeValidator;
import com.codepilot.module.command.fix.FixRequestAssembler;
import com.codepilot.module.command.fix.FixResultCommenter;
import com.codepilot.module.command.fix.FixSnippetBuilder;
import com.codepilot.module.command.git.GitPatchExecutionRequest;
import com.codepilot.module.command.git.GitPatchExecutionResult;
import com.codepilot.module.command.git.GitPatchExecutor;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.command.state.PrCommandTaskStateManager;
import com.codepilot.module.agent.dto.CodeFixResult;
import com.codepilot.module.agent.service.CodeFixService;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import com.codepilot.module.review.entity.ReviewIssue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.retry.context.RetryContextSupport;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrCommandTaskServiceImplTest {

    @AfterEach
    void clearRetryContext() {
        RetrySynchronizationManager.clear();
    }

    @Test
    void shouldReuseExistingFixTaskForSameCommentId() {
        TestContext context = new TestContext();
        PrCommandTask existingTask = existingFixTask(99L, "PENDING");
        when(context.mapper.selectList(any())).thenReturn(List.of(existingTask));

        PrCommandTask task = context.service.createFixTask(payload());

        assertThat(task).isSameAs(existingTask);
        verify(context.mapper, never()).insert(any(PrCommandTask.class));
    }

    @Test
    void shouldReuseConcurrentlyCreatedFixTaskWhenUniqueIndexRejectsDuplicate() {
        TestContext context = new TestContext();
        PrCommandTask existingTask = existingFixTask(99L, "PENDING");
        when(context.mapper.selectList(any())).thenReturn(List.of(), List.of(existingTask));
        when(context.mapper.insert(any(PrCommandTask.class))).thenThrow(new DuplicateKeyException("duplicate"));

        PrCommandTask task = context.service.createFixTask(payload());

        assertThat(task).isSameAs(existingTask);
    }

    @Test
    void shouldInsertNewFixTaskWhenCommentHasNotBeenHandled() {
        TestContext context = new TestContext();
        when(context.mapper.selectList(any())).thenReturn(List.of());
        when(context.mapper.insert(any(PrCommandTask.class))).thenAnswer(invocation -> {
            PrCommandTask task = invocation.getArgument(0);
            task.setId(100L);
            return 1;
        });

        PrCommandTask task = context.service.createFixTask(payload());

        assertThat(task.getId()).isEqualTo(100L);
        assertThat(task.getCommandType()).isEqualTo("FIX");
        assertThat(task.getStatus()).isEqualTo("PENDING");
        assertThat(task.getCommentId()).isEqualTo(99L);
    }

    @Test
    void shouldPersistOnlyRedactedCommentBodyPreview() {
        TestContext context = new TestContext();
        when(context.mapper.selectList(any())).thenReturn(List.of());
        String secret = "ghp_123456789012345678901234567890123456";
        GitHubPullRequestWebhookPayload payload = payload();
        payload.setCommentBody("@x-pilotx fix token=" + secret);
        when(context.mapper.insert(any(PrCommandTask.class))).thenAnswer(invocation -> {
            PrCommandTask task = invocation.getArgument(0);
            task.setId(100L);
            return 1;
        });

        PrCommandTask task = context.service.createFixTask(payload);

        assertThat(task.getCommentBody())
                .contains("[REDACTED]")
                .doesNotContain(secret);
    }

    @Test
    void shouldSkipTerminalFixTaskMessage() {
        TestContext context = new TestContext();
        when(context.mapper.selectById(1L)).thenReturn(existingFixTask(99L, "SUCCESS"));

        context.service.processFixTask(1L);

        verify(context.mapper, never()).updateById(any(PrCommandTask.class));
        verify(context.githubClient, never()).getPullRequestDetail(any(), any(), any());
    }

    @Test
    void shouldRethrowRetryableFixFailureWithoutTerminalCommentBeforeFinalAttempt() {
        TestContext context = new TestContext();
        context.stubRunnableFixTask();
        when(context.gitPatchExecutor.execute(any(GitPatchExecutionRequest.class)))
                .thenReturn(GitPatchExecutionResult.retryableFailure("temporary validation runner error", "detail"));
        ReflectionTestUtils.setField(context.commandTaskFailureHandler, "rabbitRetryMaxAttempts", 3);
        registerRetryContext(0);

        assertThatThrownBy(() -> context.service.processFixTask(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PR fix command task failed");

        verify(context.mapper, atLeastOnce()).updateById(context.taskCaptor.capture());
        PrCommandTask lastUpdate = context.taskCaptor.getAllValues().getLast();
        assertThat(lastUpdate.getStatus()).isEqualTo("RUNNING");
        assertThat(lastUpdate.getErrorMessage()).contains("temporary validation runner error");
        verify(context.githubClient, never()).createPullRequestComment(any(), any(), anyInt(), any());
        verify(context.commandTaskLogService).record(eq(1L), eq("RETRYING"), eq(false),
                eq("temporary validation runner error"), org.mockito.ArgumentMatchers.contains("attempt=1/3"));
    }

    @Test
    void shouldMarkFixTaskFailedAndCommentOnFinalRetryAttempt() {
        TestContext context = new TestContext();
        context.stubRunnableFixTask();
        when(context.gitPatchExecutor.execute(any(GitPatchExecutionRequest.class)))
                .thenReturn(GitPatchExecutionResult.retryableFailure("validation failed", "detail"));
        ReflectionTestUtils.setField(context.commandTaskFailureHandler, "rabbitRetryMaxAttempts", 3);
        registerRetryContext(2);

        assertThatThrownBy(() -> context.service.processFixTask(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PR fix command task failed");

        verify(context.mapper, atLeastOnce()).updateById(context.taskCaptor.capture());
        PrCommandTask lastUpdate = context.taskCaptor.getAllValues().getLast();
        assertThat(lastUpdate.getStatus()).isEqualTo("FAILED");
        assertThat(lastUpdate.getErrorMessage()).contains("validation failed");
        verify(context.githubClient).createPullRequestComment(eq("liche719"), eq("codeAireview"), eq(12),
                org.mockito.ArgumentMatchers.contains("validation failed"));
        verify(context.commandTaskLogService).record(eq(1L), eq("FAILED"), eq(false),
                eq("validation failed"), org.mockito.ArgumentMatchers.contains("attempt=3/3"));
    }

    @Test
    void shouldRedactPatchExecutionFailureBeforePersistingOrCommenting() {
        TestContext context = new TestContext();
        context.stubRunnableFixTask();
        String secret = "ghp_123456789012345678901234567890123456";
        when(context.gitPatchExecutor.execute(any(GitPatchExecutionRequest.class)))
                .thenReturn(GitPatchExecutionResult.failure("validation failed token=" + secret, "detail"));

        context.service.processFixTask(1L);

        verify(context.mapper, atLeastOnce()).updateById(context.taskCaptor.capture());
        PrCommandTask failedUpdate = context.taskCaptor.getAllValues().stream()
                .filter(task -> "FAILED".equals(task.getStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(failedUpdate.getErrorMessage())
                .contains("[REDACTED]")
                .doesNotContain(secret);
        verify(context.githubClient).createPullRequestComment(eq("liche719"), eq("codeAireview"), eq(12),
                org.mockito.ArgumentMatchers.argThat(body ->
                        body.contains("[REDACTED]") && !body.contains(secret)
                ));
    }

    @Test
    void shouldPersistOnlyRedactedGeneratedPatchPreview() {
        TestContext context = new TestContext();
        context.stubRunnableFixTaskWithPatch("""
                diff --git a/src/main/java/Demo.java b/src/main/java/Demo.java
                --- a/src/main/java/Demo.java
                +++ b/src/main/java/Demo.java
                @@ -1 +1 @@
                -old
                +String token = "ghp_123456789012345678901234567890123456";
                """);
        when(context.gitPatchExecutor.execute(any(GitPatchExecutionRequest.class)))
                .thenReturn(GitPatchExecutionResult.success("commit-sha", "pushed", "detail"));

        context.service.processFixTask(1L);

        verify(context.gitPatchExecutor).execute(context.executionRequestCaptor.capture());
        assertThat(context.executionRequestCaptor.getValue().getPatch())
                .contains("ghp_123456789012345678901234567890123456");
        verify(context.mapper, atLeastOnce()).updateById(context.taskCaptor.capture());
        PrCommandTask patchUpdate = context.taskCaptor.getAllValues().stream()
                .filter(task -> task.getGeneratedPatch() != null)
                .findFirst()
                .orElseThrow();
        assertThat(patchUpdate.getGeneratedPatch())
                .contains("[REDACTED]")
                .doesNotContain("ghp_123456789012345678901234567890123456");
    }

    private GitHubPullRequestWebhookPayload payload() {
        GitHubPullRequestWebhookPayload payload = new GitHubPullRequestWebhookPayload();
        payload.setOwner("liche719");
        payload.setRepo("codeAireview");
        payload.setPullNumber(12);
        payload.setPrUrl("https://github.com/liche719/codeAireview/pull/12");
        payload.setTitle("Fix SQL risk");
        payload.setHeadSha("abc123");
        payload.setCommentId(99L);
        payload.setCommentBody("@x-pilotx fix");
        payload.setCommentUserLogin("reviewer");
        payload.setDryRun(true);
        return payload;
    }

    private PrCommandTask existingFixTask(Long commentId, String status) {
        return buildExistingFixTask(commentId, status);
    }

    private static PrCommandTask buildExistingFixTask(Long commentId, String status) {
        PrCommandTask task = new PrCommandTask();
        task.setId(1L);
        task.setCommandType("FIX");
        task.setStatus(status);
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(12);
        task.setCommentId(commentId);
        task.setDryRun(true);
        return task;
    }

    private void registerRetryContext(int retryCount) {
        RetryContextSupport context = new RetryContextSupport(null);
        for (int i = 0; i < retryCount; i++) {
            context.registerThrowable(new RuntimeException("retry-" + i));
        }
        RetrySynchronizationManager.register(context);
    }

    private static class TestContext {

        private final PrCommandTaskMapper mapper = mock(PrCommandTaskMapper.class);

        private final GithubClient githubClient = mock(GithubClient.class);

        private final CodeFixService codeFixService = mock(CodeFixService.class);

        private final GitPatchExecutor gitPatchExecutor = mock(GitPatchExecutor.class);

        private final PrCommandTaskLogService commandTaskLogService = mock(PrCommandTaskLogService.class);

        private final GithubCommandProperties properties = new GithubCommandProperties();

        private final FixPatchScopeValidator fixPatchScopeValidator = new FixPatchScopeValidator(properties);

        private final FixableIssueSelector fixableIssueSelector = mock(FixableIssueSelector.class);

        private final FixSnippetBuilder fixSnippetBuilder = mock(FixSnippetBuilder.class);

        private final FixRequestAssembler fixRequestAssembler = new FixRequestAssembler(properties, new ObjectMapper());

        private final FixResultCommenter fixResultCommenter = new FixResultCommenter(githubClient);

        private final PrCommandTaskStateManager commandTaskStateManager = new PrCommandTaskStateManager(mapper);

        private final PrCommandTaskFailureHandler commandTaskFailureHandler =
                new PrCommandTaskFailureHandler(commandTaskStateManager, commandTaskLogService, fixResultCommenter);

        private final org.mockito.ArgumentCaptor<PrCommandTask> taskCaptor =
                org.mockito.ArgumentCaptor.forClass(PrCommandTask.class);

        private final org.mockito.ArgumentCaptor<GitPatchExecutionRequest> executionRequestCaptor =
                org.mockito.ArgumentCaptor.forClass(GitPatchExecutionRequest.class);

        private final PrCommandTaskServiceImpl service;

        private TestContext() {
            service = new PrCommandTaskServiceImpl(
                    properties,
                    githubClient,
                    codeFixService,
                    gitPatchExecutor,
                    commandTaskLogService,
                    fixPatchScopeValidator,
                    fixableIssueSelector,
                    fixSnippetBuilder,
                    fixRequestAssembler,
                    fixResultCommenter,
                    commandTaskStateManager,
                    commandTaskFailureHandler
            );
            ReflectionTestUtils.setField(service, "baseMapper", mapper);
            ReflectionTestUtils.setField(fixRequestAssembler, "githubToken", "github-token");
        }

        private void stubRunnableFixTask() {
            stubRunnableFixTaskWithPatch(defaultPatch());
        }

        private void stubRunnableFixTaskWithPatch(String patch) {
            when(mapper.selectById(1L)).thenReturn(buildExistingFixTask(99L, "PENDING"));
            when(githubClient.getPullRequestDetail("liche719", "codeAireview", 12)).thenReturn(prDetail());
            when(fixableIssueSelector.select(any(PrCommandTask.class))).thenReturn(List.of(fixableIssue()));
            when(fixSnippetBuilder.build(any(PrCommandTask.class), eq("head-sha"), any())).thenReturn("""
                    文件：src/main/java/Demo.java 行 1-4
                    1: class Demo {
                    2:   void run() {
                    3:     System.out.println("old");
                    4:   }
                    """);
            when(codeFixService.generateFix(eq(1L), any(), any(), any())).thenReturn(fixResult(patch));
        }

        private GithubPullRequestDetail prDetail() {
            GithubPullRequestDetail detail = new GithubPullRequestDetail();
            detail.setHeadSha("head-sha");
            detail.setHeadRef("feature/fix");
            detail.setHeadRepoFullName("liche719/codeAireview");
            detail.setBaseRepoFullName("liche719/codeAireview");
            detail.setHeadRepoCloneUrl("https://github.com/liche719/codeAireview.git");
            return detail;
        }

        private com.codepilot.module.review.entity.ReviewIssue fixableIssue() {
            com.codepilot.module.review.entity.ReviewIssue issue = new com.codepilot.module.review.entity.ReviewIssue();
            issue.setFilePath("src/main/java/Demo.java");
            issue.setLineNumber(2);
            issue.setIssueType("BUG_RISK");
            issue.setSeverity("HIGH");
            issue.setTitle("Demo bug");
            issue.setDescription("Fix demo bug");
            issue.setSuggestion("Update the line");
            return issue;
        }

        private String defaultPatch() {
            return """
                    diff --git a/src/main/java/Demo.java b/src/main/java/Demo.java
                    --- a/src/main/java/Demo.java
                    +++ b/src/main/java/Demo.java
                    @@ -1 +1 @@
                    -old
                    +new
                    """;
        }

        private CodeFixResult fixResult(String patch) {
            CodeFixResult result = new CodeFixResult();
            result.setSummary("Fix demo bug");
            result.setPatch(patch);
            result.setCommitMessage("fix: demo bug");
            return result;
        }
    }
}
