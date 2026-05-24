package com.codepilot.module.command.state;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class PrCommandTaskStateManager {

    private static final int GENERATED_PATCH_AUDIT_LIMIT = 4000;

    private static final int ERROR_MESSAGE_LIMIT = 2000;

    private final PrCommandTaskMapper commandTaskMapper;

    public boolean isTerminalStatus(String status) {
        return "SUCCESS".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status);
    }

    public void markRunning(PrCommandTask task) {
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
        task.setStatus("SUCCESS");
        task.setCommitSha(commitSha);
        task.setErrorMessage(null);
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        update(task);
    }

    public void markRetrying(PrCommandTask task, String message) {
        task.setStatus("RUNNING");
        task.setErrorMessage(truncate(message, ERROR_MESSAGE_LIMIT));
        task.setUpdatedAt(LocalDateTime.now());
        update(task);
    }

    public void markFailed(PrCommandTask task, String message) {
        task.setStatus("FAILED");
        task.setErrorMessage(truncate(message, ERROR_MESSAGE_LIMIT));
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        update(task);
    }

    private void update(PrCommandTask task) {
        commandTaskMapper.updateById(task);
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
