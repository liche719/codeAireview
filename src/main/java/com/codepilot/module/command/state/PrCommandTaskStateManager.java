package com.codepilot.module.command.state;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.command.fix.FixPatchArtifact;
import com.codepilot.module.command.config.PrCommandTaskProperties;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class PrCommandTaskStateManager {

    private static final int GENERATED_PATCH_AUDIT_LIMIT = 4000;

    private static final int ERROR_MESSAGE_LIMIT = 2000;

    private static final int RESPONSE_BODY_AUDIT_LIMIT = 4000;

    private final PrCommandTaskMapper commandTaskMapper;

    private final PrCommandTaskProperties properties;

    public PrCommandTaskStateManager(PrCommandTaskMapper commandTaskMapper) {
        this(commandTaskMapper, new PrCommandTaskProperties());
    }

    public PrCommandTask claim(Long taskId) {
        LocalDateTime now = LocalDateTime.now();
        String executionToken = UUID.randomUUID().toString();
        int updated = commandTaskMapper.claimForExecution(
                taskId, executionToken, now, now.plus(properties.getExecutionLease()));
        if (updated == 0) {
            return null;
        }
        PrCommandTask task = commandTaskMapper.selectById(taskId);
        if (task == null || !executionToken.equals(task.getExecutionToken())) {
            throw new IllegalStateException("claimed PR command task could not be reloaded, commandTaskId=" + taskId);
        }
        return task;
    }

    public boolean isTerminalStatus(String status) {
        return "SUCCESS".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status);
    }

    public void markRunning(PrCommandTask task) {
        if (StringUtils.hasText(task.getExecutionToken()) && "RUNNING".equalsIgnoreCase(task.getStatus())) {
            return;
        }
        task.setStatus("RUNNING");
        task.setStartedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        update(task);
    }

    public void updateHeadSha(PrCommandTask task, String headSha) {
        task.setHeadSha(headSha);
        task.setUpdatedAt(LocalDateTime.now());
        update(task);
    }

    public void storeGeneratedPatchPreview(PrCommandTask task, String patch) {
        task.setGeneratedPatch(SensitiveDataSanitizer.redactAndTruncate(patch, GENERATED_PATCH_AUDIT_LIMIT));
        task.setUpdatedAt(LocalDateTime.now());
        update(task);
    }

    public void markSuccess(PrCommandTask task, String commitSha) {
        String ownershipToken = task.getExecutionToken();
        task.setStatus("SUCCESS");
        task.setCommitSha(commitSha);
        task.setErrorMessage(null);
        task.setFinishedAt(LocalDateTime.now());
        task.setExecutionToken(null);
        task.setLeaseUntil(null);
        task.setUpdatedAt(LocalDateTime.now());
        update(task, ownershipToken);
    }

    public void storeGeneratedPatchArtifact(PrCommandTask task, FixPatchArtifact artifact) {
        task.setGeneratedPatch(SensitiveDataSanitizer.redactAndTruncate(
                artifact.payload(), GENERATED_PATCH_AUDIT_LIMIT));
        task.setPatchPayload(artifact.payload());
        task.setPatchSha256(artifact.sha256());
        task.setAllowedPathsJson(artifact.allowedPathsJson());
        task.setExecutionStage("PREVIEW_VALIDATED");
        task.setUpdatedAt(LocalDateTime.now());
        update(task);
    }

    public void storePublishProgress(
            PrCommandTask task,
            String executionStage,
            String commitSha,
            Integer pullRequestNumber,
            String pullRequestUrl
    ) {
        task.setExecutionStage(executionStage);
        if (StringUtils.hasText(commitSha)) {
            task.setCommitSha(commitSha);
        }
        if (pullRequestNumber != null) {
            task.setCreatedPrNumber(pullRequestNumber);
        }
        if (StringUtils.hasText(pullRequestUrl)) {
            task.setCreatedPrUrl(pullRequestUrl);
        }
        task.setUpdatedAt(LocalDateTime.now());
        update(task);
    }

    public void storeResponsePreview(PrCommandTask task, String responseBody) {
        task.setResponseBody(SensitiveDataSanitizer.redactAndTruncate(responseBody, RESPONSE_BODY_AUDIT_LIMIT));
        task.setUpdatedAt(LocalDateTime.now());
        update(task);
    }

    public void markRetrying(PrCommandTask task, String message) {
        String ownershipToken = task.getExecutionToken();
        task.setStatus(StringUtils.hasText(ownershipToken) ? "PENDING" : "RUNNING");
        task.setErrorMessage(truncate(message, ERROR_MESSAGE_LIMIT));
        if (StringUtils.hasText(ownershipToken)) {
            task.setExecutionToken(null);
            task.setLeaseUntil(null);
        }
        task.setUpdatedAt(LocalDateTime.now());
        update(task, ownershipToken);
    }

    public void markFailed(PrCommandTask task, String message) {
        String ownershipToken = task.getExecutionToken();
        task.setStatus("FAILED");
        task.setErrorMessage(truncate(message, ERROR_MESSAGE_LIMIT));
        task.setFinishedAt(LocalDateTime.now());
        task.setExecutionToken(null);
        task.setLeaseUntil(null);
        task.setUpdatedAt(LocalDateTime.now());
        update(task, ownershipToken);
    }

    public void markWaiting(PrCommandTask task) {
        String ownershipToken = task.getExecutionToken();
        task.setStatus("WAITING_WORKFLOW");
        task.setExecutionToken(null);
        task.setLeaseUntil(null);
        task.setUpdatedAt(LocalDateTime.now());
        update(task, ownershipToken);
    }

    public boolean resumeWaiting(Long taskId) {
        return commandTaskMapper.resumeWaiting(taskId, LocalDateTime.now()) == 1;
    }

    private void update(PrCommandTask task) {
        update(task, task.getExecutionToken());
    }

    private void update(PrCommandTask task, String ownershipToken) {
        if (!StringUtils.hasText(ownershipToken)) {
            commandTaskMapper.updateById(task);
            return;
        }
        int updated = commandTaskMapper.update(
                task,
                new LambdaUpdateWrapper<PrCommandTask>()
                        .eq(PrCommandTask::getId, task.getId())
                        .eq(PrCommandTask::getExecutionToken, ownershipToken)
        );
        if (updated == 0) {
            throw new IllegalStateException(
                    "PR command task execution ownership lost, commandTaskId=" + task.getId());
        }
    }

    private String truncate(String content, int maxLength) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        String redacted = SensitiveDataSanitizer.redact(content);
        if (redacted.length() <= maxLength) {
            return redacted;
        }
        return SensitiveDataSanitizer.truncatePreservingRedactionMarker(redacted, maxLength) + "...";
    }
}
