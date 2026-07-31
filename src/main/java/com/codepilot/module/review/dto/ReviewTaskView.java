package com.codepilot.module.review.dto;

import com.codepilot.module.review.entity.ReviewTask;

import java.time.LocalDateTime;

public record ReviewTaskView(
        Long id,
        Long userId,
        String repoOwner,
        String repoName,
        Integer prNumber,
        String prUrl,
        String title,
        String headSha,
        String reviewCommentMode,
        String status,
        Integer totalFiles,
        Integer totalIssues,
        String riskLevel,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ReviewTaskView from(ReviewTask task) {
        return new ReviewTaskView(
                task.getId(), task.getUserId(), task.getRepoOwner(), task.getRepoName(), task.getPrNumber(),
                task.getPrUrl(), task.getTitle(), task.getHeadSha(), task.getReviewCommentMode(), task.getStatus(),
                task.getTotalFiles(), task.getTotalIssues(), task.getRiskLevel(), task.getErrorMessage(),
                task.getStartedAt(), task.getFinishedAt(), task.getCreatedAt(), task.getUpdatedAt()
        );
    }
}
