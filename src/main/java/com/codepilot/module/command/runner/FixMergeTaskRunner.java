package com.codepilot.module.command.runner;

import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.failure.PrCommandTaskFailureHandler;
import com.codepilot.module.command.fix.FixResultCommenter;
import com.codepilot.module.command.fix.NonRetryableFixTaskException;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.command.state.PrCommandTaskStateManager;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubMergeResult;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class FixMergeTaskRunner {

    private static final Set<String> ALLOWED_MERGE_METHODS = Set.of("merge", "squash", "rebase");

    private final GithubCommandProperties properties;

    private final GithubClient githubClient;

    private final PrCommandTaskStateManager stateManager;

    private final PrCommandTaskLogService logService;

    private final FixResultCommenter resultCommenter;

    private final PrCommandTaskFailureHandler failureHandler;

    public void run(PrCommandTask task) {
        stateManager.markRunning(task);
        try {
            if (!properties.isFixMergeEnabled()) {
                throw new NonRetryableFixTaskException("Fix PR merging is disabled by server configuration.");
            }
            requireMergeTask(task);
            logService.record(task.getId(), "MERGE_START", true,
                    "Approved fix PR merge started.", "publishTaskId=" + task.getSourceTaskId());

            GithubPullRequestDetail sourcePullRequest = githubClient.getPullRequestDetail(
                    task.getRepoOwner(), task.getRepoName(), task.getPrNumber());
            requireFreshSource(task, sourcePullRequest);
            GithubPullRequestDetail fixPullRequest = githubClient.getPullRequestDetail(
                    task.getRepoOwner(), task.getRepoName(), task.getCreatedPrNumber());

            if (Boolean.TRUE.equals(fixPullRequest.getMerged())) {
                String mergeSha = StringUtils.hasText(fixPullRequest.getMergeCommitSha())
                        ? fixPullRequest.getMergeCommitSha()
                        : task.getCommitSha();
                stateManager.storePublishProgress(task, "MERGED", mergeSha,
                        task.getCreatedPrNumber(), task.getCreatedPrUrl());
                stateManager.markSuccess(task, mergeSha);
                resultCommenter.fixPullRequestMerged(task);
                return;
            }

            validateFixPullRequest(task, sourcePullRequest, fixPullRequest);
            String mergeMethod = normalizedMergeMethod();
            GithubMergeResult result = githubClient.mergePullRequest(
                    task.getRepoOwner(),
                    task.getRepoName(),
                    task.getCreatedPrNumber(),
                    task.getCommitSha(),
                    mergeMethod,
                    "fix: merge approved CodePilot changes for PR #" + task.getPrNumber());
            if (!Boolean.TRUE.equals(result.getMerged()) || !StringUtils.hasText(result.getSha())) {
                throw new NonRetryableFixTaskException(
                        StringUtils.hasText(result.getMessage()) ? result.getMessage() : "GitHub refused to merge the fix PR.");
            }
            stateManager.storePublishProgress(task, "MERGED", result.getSha(),
                    task.getCreatedPrNumber(), task.getCreatedPrUrl());
            stateManager.markSuccess(task, result.getSha());
            logService.record(task.getId(), "MERGED", true,
                    "Fix pull request merged.", "mergeMethod=" + mergeMethod);
            resultCommenter.fixPullRequestMerged(task);
        } catch (NonRetryableFixTaskException exception) {
            failureHandler.handleNonRetryable(task, exception);
        } catch (Exception exception) {
            failureHandler.handleRetryable(task, exception);
        }
    }

    private void requireMergeTask(PrCommandTask task) {
        if (task.getSourceTaskId() == null
                || task.getCreatedPrNumber() == null
                || !StringUtils.hasText(task.getCreatedPrUrl())
                || !StringUtils.hasText(task.getTargetBranch())
                || !StringUtils.hasText(task.getCommitSha())) {
            throw new NonRetryableFixTaskException("Approved merge task is incomplete.");
        }
    }

    private void requireFreshSource(PrCommandTask task, GithubPullRequestDetail sourcePullRequest) {
        String expectedRepo = task.getRepoOwner() + "/" + task.getRepoName();
        if (!expectedRepo.equalsIgnoreCase(sourcePullRequest.getHeadRepoFullName())
                || !expectedRepo.equalsIgnoreCase(sourcePullRequest.getBaseRepoFullName())
                || !StringUtils.hasText(task.getHeadSha())
                || !task.getHeadSha().equalsIgnoreCase(sourcePullRequest.getHeadSha())) {
            throw new NonRetryableFixTaskException(
                    "The source pull request changed after fix publishing. A new preview is required.");
        }
    }

    private void validateFixPullRequest(
            PrCommandTask task,
            GithubPullRequestDetail sourcePullRequest,
            GithubPullRequestDetail fixPullRequest
    ) {
        String expectedRepo = task.getRepoOwner() + "/" + task.getRepoName();
        if (!"open".equalsIgnoreCase(fixPullRequest.getState())) {
            throw new NonRetryableFixTaskException("Fix pull request is not open.");
        }
        if (Boolean.TRUE.equals(fixPullRequest.getDraft())) {
            throw new NonRetryableFixTaskException("Draft fix pull requests cannot be merged.");
        }
        if (!expectedRepo.equalsIgnoreCase(fixPullRequest.getHeadRepoFullName())
                || !expectedRepo.equalsIgnoreCase(fixPullRequest.getBaseRepoFullName())
                || !task.getTargetBranch().equals(fixPullRequest.getHeadRef())
                || !sourcePullRequest.getHeadRef().equals(fixPullRequest.getBaseRef())) {
            throw new NonRetryableFixTaskException("Fix pull request branch or repository does not match the approved task.");
        }
        if (!task.getCommitSha().equalsIgnoreCase(fixPullRequest.getHeadSha())) {
            throw new NonRetryableFixTaskException("Fix pull request head changed after approval.");
        }
        if (!Boolean.TRUE.equals(fixPullRequest.getMergeable())
                || !"clean".equalsIgnoreCase(fixPullRequest.getMergeableState())) {
            throw new NonRetryableFixTaskException("Fix pull request is not in a clean mergeable state.");
        }
    }

    private String normalizedMergeMethod() {
        String configured = properties.getFixMergeMethod();
        String normalized = StringUtils.hasText(configured)
                ? configured.trim().toLowerCase(Locale.ROOT)
                : "squash";
        if (!ALLOWED_MERGE_METHODS.contains(normalized)) {
            throw new NonRetryableFixTaskException("Configured fix merge method is not allowed.");
        }
        return normalized;
    }
}
