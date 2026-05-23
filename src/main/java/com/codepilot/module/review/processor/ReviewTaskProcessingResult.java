package com.codepilot.module.review.processor;

public record ReviewTaskProcessingResult(
        int totalFiles,
        int totalIssues,
        String riskLevel
) {
}
