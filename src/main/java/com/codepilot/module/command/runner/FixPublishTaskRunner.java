package com.codepilot.module.command.runner;

import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.failure.PrCommandTaskFailureHandler;
import com.codepilot.module.command.fix.FixPatchArtifactCodec;
import com.codepilot.module.command.fix.FixRequestAssembler;
import com.codepilot.module.command.fix.FixResultCommenter;
import com.codepilot.module.command.fix.NonRetryableFixTaskException;
import com.codepilot.module.command.git.GitPatchExecutionRequest;
import com.codepilot.module.command.git.GitPatchExecutionResult;
import com.codepilot.module.command.git.GitPatchExecutor;
import com.codepilot.module.command.policy.FixPullRequestWritePolicy;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.command.state.PrCommandTaskStateManager;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class FixPublishTaskRunner {

    private final GithubClient githubClient;

    private final GitPatchExecutor gitPatchExecutor;

    private final FixRequestAssembler fixRequestAssembler;

    private final FixPatchArtifactCodec patchArtifactCodec;

    private final FixPullRequestWritePolicy writePolicy;

    private final PrCommandTaskStateManager stateManager;

    private final PrCommandTaskLogService logService;

    private final FixResultCommenter resultCommenter;

    private final PrCommandTaskFailureHandler failureHandler;

    public void run(PrCommandTask task) {
        stateManager.markRunning(task);
        try {
            logService.record(task.getId(), "PUBLISH_START", true,
                    "Approved fix publishing started.", "sourceTaskId=" + task.getSourceTaskId());
            requirePublishArtifact(task);
            GithubPullRequestDetail sourcePullRequest = githubClient.getPullRequestDetail(
                    task.getRepoOwner(), task.getRepoName(), task.getPrNumber());
            writePolicy.assertWritableSameRepo(task, sourcePullRequest);
            requireFreshSource(task, sourcePullRequest);

            Set<String> allowedPaths = patchArtifactCodec.decodeAllowedPaths(task.getAllowedPathsJson());
            String commitSha = task.getCommitSha();
            if (!StringUtils.hasText(commitSha)) {
                GitPatchExecutionRequest request = fixRequestAssembler.buildExecutionRequest(
                        task,
                        sourcePullRequest,
                        task.getPatchPayload(),
                        "fix: apply approved CodePilot preview " + task.getSourceTaskId(),
                        allowedPaths);
                GitPatchExecutionResult executionResult = gitPatchExecutor.execute(request);
                if (!executionResult.isSuccess()) {
                    if (executionResult.isRetryable()) {
                        throw new IllegalStateException(executionResult.getMessage());
                    }
                    throw new NonRetryableFixTaskException(executionResult.getMessage());
                }
                commitSha = executionResult.getCommitSha();
                if (!StringUtils.hasText(commitSha)) {
                    throw new IllegalStateException("Published fix branch did not return a commit SHA.");
                }
                stateManager.storePublishProgress(task, "BRANCH_PUSHED", commitSha, null, null);
                logService.record(task.getId(), "BRANCH_PUSHED", true,
                        "Approved fix branch pushed.", "targetBranch=" + task.getTargetBranch());
            }

            GithubPullRequestDetail fixPullRequest = task.getCreatedPrNumber() == null
                    ? findOrCreateFixPullRequest(task, sourcePullRequest)
                    : githubClient.getPullRequestDetail(
                    task.getRepoOwner(), task.getRepoName(), task.getCreatedPrNumber());
            stateManager.storePublishProgress(task, "PR_CREATED", commitSha,
                    fixPullRequest.getNumber(), fixPullRequest.getHtmlUrl());
            stateManager.markSuccess(task, commitSha);
            logService.record(task.getId(), "PR_CREATED", true,
                    "Fix pull request created.", "pullNumber=" + fixPullRequest.getNumber());
            resultCommenter.fixPullRequestCreated(task);
        } catch (NonRetryableFixTaskException exception) {
            failureHandler.handleNonRetryable(task, exception);
        } catch (Exception exception) {
            failureHandler.handleRetryable(task, exception);
        }
    }

    private GithubPullRequestDetail findOrCreateFixPullRequest(
            PrCommandTask task,
            GithubPullRequestDetail sourcePullRequest
    ) {
        GithubPullRequestDetail existing = githubClient.findOpenPullRequestByHead(
                task.getRepoOwner(), task.getRepoName(), task.getTargetBranch(), sourcePullRequest.getHeadRef());
        if (existing != null) {
            return existing;
        }
        return githubClient.createPullRequest(
                task.getRepoOwner(),
                task.getRepoName(),
                buildTitle(task),
                task.getTargetBranch(),
                sourcePullRequest.getHeadRef(),
                "CodePilot approved fix for original PR #" + task.getPrNumber()
                        + ".\n\nPreview task: " + task.getSourceTaskId());
    }

    private void requirePublishArtifact(PrCommandTask task) {
        if (task.getSourceTaskId() == null
                || !StringUtils.hasText(task.getTargetBranch())
                || !patchArtifactCodec.matches(task.getPatchPayload(), task.getPatchSha256())
                || patchArtifactCodec.decodeAllowedPaths(task.getAllowedPathsJson()).isEmpty()) {
            throw new NonRetryableFixTaskException("Approved fix artifact is incomplete or corrupted.");
        }
    }

    private void requireFreshSource(PrCommandTask task, GithubPullRequestDetail detail) {
        if (!StringUtils.hasText(task.getHeadSha())
                || !task.getHeadSha().equalsIgnoreCase(detail.getHeadSha())) {
            throw new NonRetryableFixTaskException(
                    "The source pull request changed after approval. Generate and approve a new preview.");
        }
    }

    private String buildTitle(PrCommandTask task) {
        String title = StringUtils.hasText(task.getTitle()) ? task.getTitle() : "PR #" + task.getPrNumber();
        String normalized = title.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        String result = "fix: CodePilot approved changes for " + normalized;
        return result.length() <= 200 ? result : result.substring(0, 200);
    }
}
