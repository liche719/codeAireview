package com.codepilot.module.command.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.agent.dto.CodeFixResult;
import com.codepilot.module.agent.service.CodeFixService;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.failure.PrCommandTaskFailureHandler;
import com.codepilot.module.command.fix.FixableIssueSelector;
import com.codepilot.module.command.fix.FixPromptInput;
import com.codepilot.module.command.fix.FixPatchScopeValidationResult;
import com.codepilot.module.command.fix.FixPatchScopeValidator;
import com.codepilot.module.command.fix.FixRequestAssembler;
import com.codepilot.module.command.fix.FixResultCommenter;
import com.codepilot.module.command.fix.FixSnippetBuilder;
import com.codepilot.module.command.fix.NonRetryableFixTaskException;
import com.codepilot.module.command.git.GitPatchExecutionRequest;
import com.codepilot.module.command.git.GitPatchExecutionResult;
import com.codepilot.module.command.git.GitPatchExecutor;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.command.policy.FixPullRequestWritePolicy;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.command.service.PrCommandTaskService;
import com.codepilot.module.command.state.PrCommandTaskStateManager;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import com.codepilot.module.review.entity.ReviewIssue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrCommandTaskServiceImpl extends ServiceImpl<PrCommandTaskMapper, PrCommandTask>
        implements PrCommandTaskService {

    private static final int COMMENT_BODY_AUDIT_LIMIT = 2000;

    private final GithubClient githubClient;

    private final CodeFixService codeFixService;

    private final GitPatchExecutor gitPatchExecutor;

    private final PrCommandTaskLogService commandTaskLogService;

    private final FixPatchScopeValidator fixPatchScopeValidator;

    private final FixableIssueSelector fixableIssueSelector;

    private final FixSnippetBuilder fixSnippetBuilder;

    private final FixRequestAssembler fixRequestAssembler;

    private final FixResultCommenter fixResultCommenter;

    private final PrCommandTaskStateManager commandTaskStateManager;

    private final PrCommandTaskFailureHandler commandTaskFailureHandler;

    private final FixPullRequestWritePolicy fixPullRequestWritePolicy;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrCommandTask createFixTask(GitHubPullRequestWebhookPayload payload) {
        PrCommandTask existingTask = findExistingFixTask(payload);
        if (existingTask != null) {
            log.info("Reuse existing PR fix command task, commandTaskId={}, owner={}, repo={}, pullNumber={}, commentId={}, status={}",
                    existingTask.getId(),
                    payload.getOwner(),
                    payload.getRepo(),
                    payload.getPullNumber(),
                    payload.getCommentId(),
                    existingTask.getStatus());
            return existingTask;
        }

        PrCommandTask task = new PrCommandTask();
        task.setCommandType("FIX");
        task.setStatus("PENDING");
        task.setRepoOwner(payload.getOwner());
        task.setRepoName(payload.getRepo());
        task.setPrNumber(payload.getPullNumber());
        task.setPrUrl(payload.getPrUrl());
        task.setTitle(payload.getTitle());
        task.setHeadSha(payload.getHeadSha());
        task.setCommentId(payload.getCommentId());
        task.setCommentBody(commentBodyAuditPreview(payload.getCommentBody()));
        task.setCommentUserLogin(payload.getCommentUserLogin());
        task.setDryRun(Boolean.TRUE.equals(payload.getDryRun()));
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        try {
            save(task);
        } catch (DuplicateKeyException exception) {
            PrCommandTask duplicateTask = findExistingFixTask(payload);
            if (duplicateTask != null) {
                log.info("Reuse concurrently created PR fix command task, commandTaskId={}, owner={}, repo={}, pullNumber={}, commentId={}",
                        duplicateTask.getId(),
                        payload.getOwner(),
                        payload.getRepo(),
                        payload.getPullNumber(),
                        payload.getCommentId());
                return duplicateTask;
            }
            throw exception;
        }
        return task;
    }

    @Override
    public void processFixTask(Long commandTaskId) {
        PrCommandTask task = getById(commandTaskId);
        if (task == null) {
            log.warn("PR command task not found, commandTaskId={}", commandTaskId);
            return;
        }

        if (commandTaskStateManager.isTerminalStatus(task.getStatus())) {
            log.info("Skip terminal PR command task message, commandTaskId={}, status={}",
                    commandTaskId, task.getStatus());
            return;
        }

        commandTaskStateManager.markRunning(task);
        try {
            commandTaskLogService.record(task.getId(), "START", true, "修复命令已开始。", null);
            GithubPullRequestDetail detail = githubClient.getPullRequestDetail(
                    task.getRepoOwner(),
                    task.getRepoName(),
                    task.getPrNumber()
            );
            if (!StringUtils.hasText(detail.getHeadSha())) {
                throw new IllegalStateException("PR head sha is required before generating a fix");
            }
            commandTaskStateManager.updateHeadSha(task, detail.getHeadSha());

            fixPullRequestWritePolicy.assertWritableSameRepo(task, detail);
            List<ReviewIssue> fixableIssues = fixableIssueSelector.select(task);
            if (fixableIssues.isEmpty()) {
                commandTaskStateManager.markFailed(task, "未找到可修复的受支持 review 问题。");
                fixResultCommenter.noFixableIssues(task);
                return;
            }

            FixPromptInput promptInput = fixRequestAssembler.buildPromptInput(fixableIssues);
            String snippets = fixSnippetBuilder.build(task, detail.getHeadSha(), fixableIssues);
            if (!StringUtils.hasText(snippets)) {
                commandTaskStateManager.markFailed(task, "找到了可修复的问题，但没有有效的代码片段。");
                fixResultCommenter.missingSnippetContext(task);
                return;
            }
            CodeFixResult fixResult = codeFixService.generateFix(
                    task.getId(),
                    promptInput.issuesJson(),
                    snippets,
                    promptInput.limits()
            );
            String patch = fixResult == null ? null : fixResult.getPatch();
            if (!StringUtils.hasText(patch)) {
                commandTaskStateManager.markFailed(task, "模型没有生成补丁。");
                fixResultCommenter.patchNotGenerated(task);
                return;
            }

            FixPatchScopeValidationResult stats = fixPatchScopeValidator.validate(patch, promptInput.allowedPaths());
            commandTaskStateManager.storeGeneratedPatchPreview(task, patch);
            commandTaskLogService.record(task.getId(), "PATCH_GENERATED", true, "补丁已生成。", stats.toString());

            GitPatchExecutionRequest request = fixRequestAssembler.buildExecutionRequest(
                    task,
                    detail,
                    patch,
                    fixResult == null ? null : fixResult.getCommitMessage()
            );
            GitPatchExecutionResult executionResult = gitPatchExecutor.execute(request);
            if (!executionResult.isSuccess()) {
                if (executionResult.isRetryable()) {
                    throw new IllegalStateException(executionResult.getMessage());
                }
                commandTaskStateManager.markFailed(task, executionResult.getMessage());
                commandTaskLogService.record(task.getId(), "PATCH_EXECUTE", false, executionResult.getMessage(), executionResult.getDetail());
                fixResultCommenter.patchValidationFailed(task, executionResult.getMessage());
                return;
            }

            commandTaskStateManager.markSuccess(task, executionResult.getCommitSha());
            commandTaskLogService.record(task.getId(), "PATCH_EXECUTE", true, executionResult.getMessage(), executionResult.getDetail());
            if (Boolean.TRUE.equals(task.getDryRun())) {
                fixResultCommenter.dryRunCompleted(task, stats, fixResult.getSummary(), patch);
            } else {
                fixResultCommenter.fixPushed(task);
            }
        } catch (NonRetryableFixTaskException exception) {
            commandTaskFailureHandler.handleNonRetryable(task, exception);
        } catch (Exception exception) {
            commandTaskFailureHandler.handleRetryable(task, exception);
        }
    }

    private PrCommandTask findExistingFixTask(GitHubPullRequestWebhookPayload payload) {
        if (payload == null || payload.getCommentId() == null) {
            return null;
        }
        List<PrCommandTask> tasks = list(new LambdaQueryWrapper<PrCommandTask>()
                .eq(PrCommandTask::getCommandType, "FIX")
                .eq(PrCommandTask::getRepoOwner, payload.getOwner())
                .eq(PrCommandTask::getRepoName, payload.getRepo())
                .eq(PrCommandTask::getPrNumber, payload.getPullNumber())
                .eq(PrCommandTask::getCommentId, payload.getCommentId())
                .orderByDesc(PrCommandTask::getId)
                .last("LIMIT 1"));
        return tasks == null || tasks.isEmpty() ? null : tasks.getFirst();
    }

    private String commentBodyAuditPreview(String commentBody) {
        return SensitiveDataSanitizer.redactAndTruncate(commentBody, COMMENT_BODY_AUDIT_LIMIT);
    }

}
