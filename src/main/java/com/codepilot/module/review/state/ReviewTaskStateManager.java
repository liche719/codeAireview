package com.codepilot.module.review.state;

import com.codepilot.common.enums.ReviewTaskStatus;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.config.ReviewProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewTaskStateManager {

    private final ReviewTaskMapper reviewTaskMapper;

    private final ReviewProperties reviewProperties;

    public ReviewTask claim(Long taskId) {
        LocalDateTime now = LocalDateTime.now();
        String executionToken = UUID.randomUUID().toString();
        int updated = reviewTaskMapper.claimForExecution(
                taskId,
                executionToken,
                now,
                now.plus(reviewProperties.getExecutionLease())
        );
        if (updated == 0) {
            return null;
        }
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null || !executionToken.equals(task.getExecutionToken())) {
            throw new IllegalStateException("claimed review task could not be reloaded, taskId=" + taskId);
        }
        return task;
    }

    public void markSuccess(ReviewTask task, int totalFiles, int totalIssues, String riskLevel) {
        int updated = reviewTaskMapper.completeExecution(
                task.getId(), task.getExecutionToken(), totalFiles, totalIssues, riskLevel, LocalDateTime.now()
        );
        assertExecutionOwned(task, updated, "complete");
    }

    public void markRetrying(ReviewTask task, String errorMessage) {
        int updated = reviewTaskMapper.releaseForRetry(
                task.getId(), task.getExecutionToken(), errorMessage, LocalDateTime.now()
        );
        assertExecutionOwned(task, updated, "release for retry");
    }

    public void markFailed(ReviewTask task, String errorMessage) {
        int updated = reviewTaskMapper.failExecution(
                task.getId(), task.getExecutionToken(), errorMessage, LocalDateTime.now()
        );
        assertExecutionOwned(task, updated, "fail");
    }

    public void updateHeadSha(ReviewTask task, String headSha) {
        task.setHeadSha(headSha);
        task.setUpdatedAt(LocalDateTime.now());
        update(task);
    }

    private void update(ReviewTask task) {
        reviewTaskMapper.updateById(task);
    }

    private void assertExecutionOwned(ReviewTask task, int updated, String operation) {
        if (updated == 0) {
            throw new IllegalStateException("review task execution ownership lost while attempting to "
                    + operation + ", taskId=" + task.getId());
        }
    }
}
