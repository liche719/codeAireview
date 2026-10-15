package com.codepilot.module.command.creator;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.entity.PrCommandTaskType;

@Slf4j
@Component
@RequiredArgsConstructor
public class PrCommandTaskCreator {

    private static final int COMMENT_BODY_AUDIT_LIMIT = 2000;

    private final PrCommandTaskMapper commandTaskMapper;

    private final GithubCommandProperties properties;

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

        PrCommandTask task = buildPendingFixTask(payload);
        try {
            commandTaskMapper.insert(task);
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

    public PrCommandTask createChatTask(GitHubPullRequestWebhookPayload payload) {
        PrCommandTask existingTask = findExistingTask(payload, PrCommandTaskType.CHAT);
        if (existingTask != null) {
            return existingTask;
        }
        PrCommandTask task = buildPendingTask(payload, PrCommandTaskType.CHAT);
        try {
            commandTaskMapper.insert(task);
            return task;
        } catch (DuplicateKeyException exception) {
            PrCommandTask duplicateTask = findExistingTask(payload, PrCommandTaskType.CHAT);
            if (duplicateTask != null) {
                return duplicateTask;
            }
            throw exception;
        }
    }

    public PrCommandTask createPublishTask(PrCommandTask approvalTask, PrCommandTask previewTask) {
        return createActionTask(approvalTask, previewTask, PrCommandTaskType.FIX_PUBLISH);
    }

    public PrCommandTask createMergeTask(PrCommandTask approvalTask, PrCommandTask publishTask) {
        return createActionTask(approvalTask, publishTask, PrCommandTaskType.FIX_MERGE);
    }

    private PrCommandTask createActionTask(
            PrCommandTask approvalTask,
            PrCommandTask sourceTask,
            String commandType
    ) {
        PrCommandTask existing = findExistingActionTask(sourceTask.getId(), commandType);
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        PrCommandTask task = new PrCommandTask();
        task.setCommandType(commandType);
        task.setStatus("PENDING");
        task.setRepoOwner(sourceTask.getRepoOwner());
        task.setRepoName(sourceTask.getRepoName());
        task.setPrNumber(sourceTask.getPrNumber());
        task.setPrUrl(sourceTask.getPrUrl());
        task.setTitle(sourceTask.getTitle());
        task.setHeadSha(sourceTask.getHeadSha());
        task.setCommentId(approvalTask.getCommentId());
        task.setCommentBody(commentBodyAuditPreview(approvalTask.getCommentBody()));
        task.setCommandText(commentBodyAuditPreview(approvalTask.getCommandText()));
        task.setCommentUserLogin(approvalTask.getCommentUserLogin());
        task.setCommentAuthorAssociation(approvalTask.getCommentAuthorAssociation());
        task.setSourceTaskId(sourceTask.getId());
        task.setApprovalCommentId(approvalTask.getCommentId());
        task.setApprovedBy(approvalTask.getCommentUserLogin());
        task.setDryRun(false);
        task.setExecutionStage("APPROVED");
        if (PrCommandTaskType.FIX_PUBLISH.equals(commandType)) {
            task.setPatchPayload(sourceTask.getPatchPayload());
            task.setPatchSha256(sourceTask.getPatchSha256());
            task.setAllowedPathsJson(sourceTask.getAllowedPathsJson());
            task.setGeneratedPatch(sourceTask.getGeneratedPatch());
            task.setTargetBranch(buildTargetBranch(sourceTask.getId()));
        } else {
            task.setTargetBranch(sourceTask.getTargetBranch());
            task.setCreatedPrNumber(sourceTask.getCreatedPrNumber());
            task.setCreatedPrUrl(sourceTask.getCreatedPrUrl());
            task.setCommitSha(sourceTask.getCommitSha());
        }
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        try {
            commandTaskMapper.insert(task);
            return task;
        } catch (DuplicateKeyException exception) {
            PrCommandTask duplicate = findExistingActionTask(sourceTask.getId(), commandType);
            if (duplicate != null) {
                return duplicate;
            }
            throw exception;
        }
    }

    private PrCommandTask findExistingActionTask(Long sourceTaskId, String commandType) {
        if (sourceTaskId == null) {
            return null;
        }
        List<PrCommandTask> tasks = commandTaskMapper.selectList(new LambdaQueryWrapper<PrCommandTask>()
                .eq(PrCommandTask::getCommandType, commandType)
                .eq(PrCommandTask::getSourceTaskId, sourceTaskId)
                .orderByDesc(PrCommandTask::getId)
                .last("LIMIT 1"));
        return tasks == null || tasks.isEmpty() ? null : tasks.getFirst();
    }

    private String buildTargetBranch(Long previewTaskId) {
        String prefix = properties.getFixBranchPrefix();
        String safePrefix = prefix == null ? "codepilot/fix-" : prefix.trim().toLowerCase(Locale.ROOT);
        if (!safePrefix.matches("[a-z0-9._/-]{1,80}") || safePrefix.startsWith("/") || safePrefix.endsWith("/")) {
            safePrefix = "codepilot/fix-";
        }
        return safePrefix + previewTaskId + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private PrCommandTask buildPendingFixTask(GitHubPullRequestWebhookPayload payload) {
        PrCommandTask task = buildPendingTask(payload, "FIX");
        task.setDryRun(Boolean.TRUE.equals(payload.getDryRun()));
        return task;
    }

    private PrCommandTask buildPendingTask(GitHubPullRequestWebhookPayload payload, String commandType) {
        LocalDateTime now = LocalDateTime.now();
        PrCommandTask task = new PrCommandTask();
        task.setCommandType(commandType);
        task.setStatus("PENDING");
        task.setRepoOwner(payload.getOwner());
        task.setRepoName(payload.getRepo());
        task.setPrNumber(payload.getPullNumber());
        task.setPrUrl(payload.getPrUrl());
        task.setTitle(payload.getTitle());
        task.setHeadSha(payload.getHeadSha());
        task.setCommentId(payload.getCommentId());
        task.setCommentBody(commentBodyAuditPreview(payload.getCommentBody()));
        task.setCommandText(commentBodyAuditPreview(payload.getCommandText()));
        task.setCommentUserLogin(payload.getCommentUserLogin());
        task.setCommentAuthorAssociation(payload.getCommentAuthorAssociation());
        task.setDryRun(false);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    private PrCommandTask findExistingFixTask(GitHubPullRequestWebhookPayload payload) {
        return findExistingTask(payload, PrCommandTaskType.FIX_PREVIEW);
    }

    private PrCommandTask findExistingTask(GitHubPullRequestWebhookPayload payload, String commandType) {
        if (payload == null || payload.getCommentId() == null) {
            return null;
        }
        List<PrCommandTask> tasks = commandTaskMapper.selectList(new LambdaQueryWrapper<PrCommandTask>()
                .eq(PrCommandTask::getCommandType, commandType)
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
