package com.codepilot.module.command.runner;

import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.failure.PrCommandTaskFailureHandler;
import com.codepilot.module.command.fix.FixResultCommenter;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.command.state.PrCommandTaskStateManager;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubMergeResult;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixMergeTaskRunnerTest {

    @Test
    void shouldMergeOnlyCleanFixPullRequestAtApprovedHeadSha() {
        GithubCommandProperties properties = new GithubCommandProperties();
        properties.setFixMergeEnabled(true);
        GithubClient githubClient = mock(GithubClient.class);
        PrCommandTaskStateManager stateManager = mock(PrCommandTaskStateManager.class);
        PrCommandTaskLogService logService = mock(PrCommandTaskLogService.class);
        FixResultCommenter commenter = mock(FixResultCommenter.class);
        PrCommandTaskFailureHandler failureHandler = mock(PrCommandTaskFailureHandler.class);
        PrCommandTask task = mergeTask();
        when(githubClient.getPullRequestDetail("liche719", "codeAireview", 12))
                .thenReturn(sourcePullRequest());
        when(githubClient.getPullRequestDetail("liche719", "codeAireview", 99))
                .thenReturn(fixPullRequest());
        GithubMergeResult result = new GithubMergeResult();
        result.setMerged(true);
        result.setSha("merge-sha");
        when(githubClient.mergePullRequest(
                "liche719", "codeAireview", 99, "fix-sha", "squash",
                "fix: merge approved CodePilot changes for PR #12"))
                .thenReturn(result);
        FixMergeTaskRunner runner = new FixMergeTaskRunner(
                properties, githubClient, stateManager, logService, commenter, failureHandler);

        runner.run(task);

        verify(githubClient).mergePullRequest(
                "liche719", "codeAireview", 99, "fix-sha", "squash",
                "fix: merge approved CodePilot changes for PR #12");
        verify(stateManager).storePublishProgress(task, "MERGED", "merge-sha", 99, task.getCreatedPrUrl());
        verify(stateManager).markSuccess(task, "merge-sha");
        verify(commenter).fixPullRequestMerged(task);
    }

    private PrCommandTask mergeTask() {
        PrCommandTask task = new PrCommandTask();
        task.setId(40L);
        task.setSourceTaskId(30L);
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(12);
        task.setHeadSha("source-sha");
        task.setTargetBranch("codepilot/fix-20-abcd1234");
        task.setCreatedPrNumber(99);
        task.setCreatedPrUrl("https://github.com/liche719/codeAireview/pull/99");
        task.setCommitSha("fix-sha");
        return task;
    }

    private GithubPullRequestDetail sourcePullRequest() {
        GithubPullRequestDetail detail = new GithubPullRequestDetail();
        detail.setHeadSha("source-sha");
        detail.setHeadRef("feature/original");
        detail.setHeadRepoFullName("liche719/codeAireview");
        detail.setBaseRepoFullName("liche719/codeAireview");
        return detail;
    }

    private GithubPullRequestDetail fixPullRequest() {
        GithubPullRequestDetail detail = new GithubPullRequestDetail();
        detail.setState("open");
        detail.setDraft(false);
        detail.setMergeable(true);
        detail.setMergeableState("clean");
        detail.setHeadSha("fix-sha");
        detail.setHeadRef("codepilot/fix-20-abcd1234");
        detail.setBaseRef("feature/original");
        detail.setHeadRepoFullName("liche719/codeAireview");
        detail.setBaseRepoFullName("liche719/codeAireview");
        return detail;
    }
}
