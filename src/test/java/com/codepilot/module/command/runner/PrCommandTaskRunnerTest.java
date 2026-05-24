package com.codepilot.module.command.runner;

import com.codepilot.module.agent.dto.CodeFixResult;
import com.codepilot.module.agent.service.CodeFixService;
import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.failure.PrCommandTaskFailureHandler;
import com.codepilot.module.command.fix.FixPatchScopeValidator;
import com.codepilot.module.command.fix.FixRequestAssembler;
import com.codepilot.module.command.fix.FixResultCommenter;
import com.codepilot.module.command.fix.FixSnippetBuilder;
import com.codepilot.module.command.fix.FixableIssueSelector;
import com.codepilot.module.command.git.GitPatchExecutionRequest;
import com.codepilot.module.command.git.GitPatchExecutionResult;
import com.codepilot.module.command.git.GitPatchExecutor;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.command.policy.FixPullRequestWritePolicy;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.command.state.PrCommandTaskStateManager;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.review.entity.ReviewIssue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrCommandTaskRunnerTest {

    @Test
    void shouldRunDryRunFixPipelineSuccessfully() {
        TestContext context = new TestContext();
        context.stubRunnableFixTask();
        when(context.gitPatchExecutor.execute(any(GitPatchExecutionRequest.class)))
                .thenReturn(GitPatchExecutionResult.success(null, "dry-run ok", "detail"));

        context.runner.run(context.task);

        verify(context.githubClient).getPullRequestDetail("liche719", "codeAireview", 12);
        verify(context.gitPatchExecutor).execute(context.executionRequestCaptor.capture());
        assertThat(context.executionRequestCaptor.getValue().isDryRun()).isTrue();
        verify(context.commandTaskLogService).record(eq(1L), eq("PATCH_EXECUTE"), eq(true), eq("dry-run ok"), eq("detail"));
        verify(context.githubClient).createPullRequestComment(eq("liche719"), eq("codeAireview"), eq(12),
                org.mockito.ArgumentMatchers.contains("预演完成"));
        verify(context.mapper, atLeastOnce()).updateById(context.taskCaptor.capture());
        PrCommandTask successUpdate = context.taskCaptor.getAllValues().stream()
                .filter(updated -> "SUCCESS".equals(updated.getStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(successUpdate.getErrorMessage()).isNull();
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

        private final FixPullRequestWritePolicy fixPullRequestWritePolicy = new FixPullRequestWritePolicy();

        private final ArgumentCaptor<PrCommandTask> taskCaptor = ArgumentCaptor.forClass(PrCommandTask.class);

        private final ArgumentCaptor<GitPatchExecutionRequest> executionRequestCaptor =
                ArgumentCaptor.forClass(GitPatchExecutionRequest.class);

        private final PrCommandTask task = task();

        private final PrCommandTaskRunner runner = new PrCommandTaskRunner(
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
                commandTaskFailureHandler,
                fixPullRequestWritePolicy
        );

        private TestContext() {
            ReflectionTestUtils.setField(fixRequestAssembler, "githubToken", "github-token");
        }

        private void stubRunnableFixTask() {
            when(githubClient.getPullRequestDetail("liche719", "codeAireview", 12)).thenReturn(prDetail());
            when(fixableIssueSelector.select(task)).thenReturn(List.of(fixableIssue()));
            when(fixSnippetBuilder.build(eq(task), eq("head-sha"), any())).thenReturn("""
                    文件：src/main/java/Demo.java 行 1-4
                    1: class Demo {
                    2:   void run() {
                    3:     System.out.println("old");
                    4:   }
                    """);
            when(codeFixService.generateFix(eq(1L), any(), any(), any())).thenReturn(fixResult());
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

        private ReviewIssue fixableIssue() {
            ReviewIssue issue = new ReviewIssue();
            issue.setFilePath("src/main/java/Demo.java");
            issue.setLineNumber(2);
            issue.setIssueType("BUG_RISK");
            issue.setSeverity("HIGH");
            issue.setTitle("Demo bug");
            issue.setDescription("Fix demo bug");
            issue.setSuggestion("Update the line");
            return issue;
        }

        private CodeFixResult fixResult() {
            CodeFixResult result = new CodeFixResult();
            result.setSummary("Fix demo bug");
            result.setPatch("""
                    diff --git a/src/main/java/Demo.java b/src/main/java/Demo.java
                    --- a/src/main/java/Demo.java
                    +++ b/src/main/java/Demo.java
                    @@ -1 +1 @@
                    -old
                    +new
                    """);
            result.setCommitMessage("fix: demo bug");
            return result;
        }

        private PrCommandTask task() {
            PrCommandTask task = new PrCommandTask();
            task.setId(1L);
            task.setStatus("PENDING");
            task.setRepoOwner("liche719");
            task.setRepoName("codeAireview");
            task.setPrNumber(12);
            task.setDryRun(true);
            return task;
        }
    }
}
