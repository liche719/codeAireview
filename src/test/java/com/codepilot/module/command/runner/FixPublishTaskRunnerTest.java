package com.codepilot.module.command.runner;

import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.failure.PrCommandTaskFailureHandler;
import com.codepilot.module.command.fix.FixPatchArtifact;
import com.codepilot.module.command.fix.FixPatchArtifactCodec;
import com.codepilot.module.command.fix.FixRequestAssembler;
import com.codepilot.module.command.fix.FixResultCommenter;
import com.codepilot.module.command.git.GitPatchExecutionRequest;
import com.codepilot.module.command.git.GitPatchExecutionResult;
import com.codepilot.module.command.git.GitPatchExecutor;
import com.codepilot.module.command.policy.FixPullRequestWritePolicy;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.command.state.PrCommandTaskStateManager;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixPublishTaskRunnerTest {

    @Test
    void shouldPushApprovedPatchToNewBranchAndCreateFixPullRequest() {
        GithubCommandProperties properties = new GithubCommandProperties();
        FixPatchArtifactCodec codec = new FixPatchArtifactCodec(new ObjectMapper(), properties);
        GithubClient githubClient = mock(GithubClient.class);
        GitPatchExecutor executor = mock(GitPatchExecutor.class);
        FixRequestAssembler assembler = mock(FixRequestAssembler.class);
        PrCommandTaskStateManager stateManager = mock(PrCommandTaskStateManager.class);
        PrCommandTaskLogService logService = mock(PrCommandTaskLogService.class);
        FixResultCommenter commenter = mock(FixResultCommenter.class);
        PrCommandTaskFailureHandler failureHandler = mock(PrCommandTaskFailureHandler.class);
        PrCommandTask task = publishTask(codec);
        GithubPullRequestDetail source = sourcePullRequest();
        GitPatchExecutionRequest request = new GitPatchExecutionRequest();
        request.setTargetBranch(task.getTargetBranch());
        GithubPullRequestDetail created = new GithubPullRequestDetail();
        created.setNumber(99);
        created.setHtmlUrl("https://github.com/liche719/codeAireview/pull/99");
        when(githubClient.getPullRequestDetail("liche719", "codeAireview", 12)).thenReturn(source);
        when(assembler.buildExecutionRequest(any(), any(), any(), any(), any())).thenReturn(request);
        when(executor.execute(request)).thenReturn(GitPatchExecutionResult.success("fix-sha", "pushed", null));
        when(githubClient.findOpenPullRequestByHead(
                "liche719", "codeAireview", task.getTargetBranch(), "feature/original"))
                .thenReturn(null);
        when(githubClient.createPullRequest(any(), any(), any(), any(), any(), any())).thenReturn(created);
        FixPublishTaskRunner runner = new FixPublishTaskRunner(
                githubClient,
                executor,
                assembler,
                codec,
                new FixPullRequestWritePolicy(),
                stateManager,
                logService,
                commenter,
                failureHandler);

        runner.run(task);

        ArgumentCaptor<String> head = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> base = ArgumentCaptor.forClass(String.class);
        verify(githubClient).createPullRequest(
                org.mockito.ArgumentMatchers.eq("liche719"),
                org.mockito.ArgumentMatchers.eq("codeAireview"),
                any(),
                head.capture(),
                base.capture(),
                any());
        assertThat(head.getValue()).isEqualTo(task.getTargetBranch());
        assertThat(base.getValue()).isEqualTo("feature/original");
        verify(stateManager).storePublishProgress(task, "BRANCH_PUSHED", "fix-sha", null, null);
        verify(stateManager).storePublishProgress(task, "PR_CREATED", "fix-sha", 99, created.getHtmlUrl());
        verify(stateManager).markSuccess(task, "fix-sha");
        verify(commenter).fixPullRequestCreated(task);
    }

    private PrCommandTask publishTask(FixPatchArtifactCodec codec) {
        PrCommandTask task = new PrCommandTask();
        task.setId(30L);
        task.setSourceTaskId(20L);
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(12);
        task.setHeadSha("source-sha");
        task.setTargetBranch("codepilot/fix-20-abcd1234");
        task.setTitle("Original change");
        FixPatchArtifact artifact = codec.encode("diff --git a/A.java b/A.java\n", Set.of("A.java"));
        task.setPatchPayload(artifact.payload());
        task.setPatchSha256(artifact.sha256());
        task.setAllowedPathsJson(artifact.allowedPathsJson());
        return task;
    }

    private GithubPullRequestDetail sourcePullRequest() {
        GithubPullRequestDetail detail = new GithubPullRequestDetail();
        detail.setHeadSha("source-sha");
        detail.setHeadRef("feature/original");
        detail.setHeadRepoFullName("liche719/codeAireview");
        detail.setBaseRepoFullName("liche719/codeAireview");
        detail.setHeadRepoCloneUrl("https://github.com/liche719/codeAireview.git");
        return detail;
    }
}
