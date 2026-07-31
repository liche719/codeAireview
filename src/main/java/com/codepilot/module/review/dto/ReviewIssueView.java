package com.codepilot.module.review.dto;

import com.codepilot.module.review.entity.ReviewIssue;

import java.time.LocalDateTime;

public record ReviewIssueView(
        Long id,
        Long taskId,
        String filePath,
        Integer lineNumber,
        String issueType,
        String issueTypeZh,
        String severity,
        String title,
        String description,
        String suggestion,
        String source,
        String ruleReference,
        Integer finalScore,
        String publishDecision,
        String suppressionReason,
        String commentChannel,
        LocalDateTime createdAt
) {

    public static ReviewIssueView from(ReviewIssue issue) {
        return new ReviewIssueView(
                issue.getId(), issue.getTaskId(), issue.getFilePath(), issue.getLineNumber(), issue.getIssueType(),
                issue.getIssueTypeZh(), issue.getSeverity(), issue.getTitle(), issue.getDescription(), issue.getSuggestion(),
                issue.getSource(), issue.getRuleReference(), issue.getFinalScore(), issue.getPublishDecision(),
                issue.getSuppressionReason(), issue.getCommentChannel(), issue.getCreatedAt()
        );
    }
}
