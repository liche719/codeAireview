package com.codepilot.module.review.state;

import com.codepilot.common.enums.ReviewTaskStatus;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class ReviewTaskStateManager {

    private final ReviewTaskMapper reviewTaskMapper;

    public void markRunning(ReviewTask task) {
        task.setStatus(ReviewTaskStatus.RUNNING.name());
        task.setStartedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        update(task);
    }

    public void markSuccess(ReviewTask task, int totalFiles, int totalIssues, String riskLevel) {
        task.setStatus(ReviewTaskStatus.SUCCESS.name());
        task.setTotalFiles(totalFiles);
        task.setTotalIssues(totalIssues);
        task.setRiskLevel(riskLevel);
        task.setErrorMessage(null);
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        update(task);
    }

    public void markRetrying(ReviewTask task, String errorMessage) {
        task.setStatus(ReviewTaskStatus.RUNNING.name());
        task.setErrorMessage(errorMessage);
        task.setUpdatedAt(LocalDateTime.now());
        update(task);
    }

    public void markFailed(ReviewTask task, String errorMessage) {
        task.setStatus(ReviewTaskStatus.FAILED.name());
        task.setErrorMessage(errorMessage);
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        update(task);
    }

    public void updateHeadSha(ReviewTask task, String headSha) {
        task.setHeadSha(headSha);
        task.setUpdatedAt(LocalDateTime.now());
        update(task);
    }

    private void update(ReviewTask task) {
        reviewTaskMapper.updateById(task);
    }
}
