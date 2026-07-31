package com.codepilot.module.review.processor;

import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;

import java.util.List;

public record ReviewTaskProcessingResult(
        int totalFiles,
        int totalIssues,
        String riskLevel,
        List<ReviewFile> reviewFiles,
        List<ReviewIssue> reviewIssues
) {

    public ReviewTaskProcessingResult {
        reviewFiles = reviewFiles == null ? List.of() : List.copyOf(reviewFiles);
        reviewIssues = reviewIssues == null ? List.of() : List.copyOf(reviewIssues);
    }
}
