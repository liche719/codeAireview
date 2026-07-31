package com.codepilot.module.review.dto;

import com.codepilot.module.review.entity.ReviewFile;

import java.time.LocalDateTime;

public record ReviewFileView(
        Long id,
        Long taskId,
        String filePath,
        String changeType,
        String patch,
        Integer additions,
        Integer deletions,
        Boolean skipped,
        String skipReason,
        LocalDateTime createdAt
) {

    public static ReviewFileView from(ReviewFile file) {
        return new ReviewFileView(
                file.getId(), file.getTaskId(), file.getFilePath(), file.getChangeType(), file.getPatch(),
                file.getAdditions(), file.getDeletions(), file.getSkipped(), file.getSkipReason(), file.getCreatedAt()
        );
    }
}
