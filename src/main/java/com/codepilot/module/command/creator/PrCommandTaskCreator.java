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

@Slf4j
@Component
@RequiredArgsConstructor
public class PrCommandTaskCreator {

    private static final int COMMENT_BODY_AUDIT_LIMIT = 2000;

    private final PrCommandTaskMapper commandTaskMapper;

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

    private PrCommandTask buildPendingFixTask(GitHubPullRequestWebhookPayload payload) {
        LocalDateTime now = LocalDateTime.now();
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
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    private PrCommandTask findExistingFixTask(GitHubPullRequestWebhookPayload payload) {
        if (payload == null || payload.getCommentId() == null) {
            return null;
        }
        List<PrCommandTask> tasks = commandTaskMapper.selectList(new LambdaQueryWrapper<PrCommandTask>()
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
