package com.codepilot.module.review.assembler;

import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.review.entity.ReviewIssue;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class ReviewIssueAssembler {

    public List<ReviewIssue> toReviewIssues(Long taskId, String defaultFilePath, AiReviewResult aiReviewResult) {
        if (aiReviewResult == null || aiReviewResult.getIssues() == null || aiReviewResult.getIssues().isEmpty()) {
            return List.of();
        }

        List<ReviewIssue> reviewIssues = new ArrayList<>();
        for (AiReviewIssue issue : aiReviewResult.getIssues()) {
            ReviewIssue reviewIssue = new ReviewIssue();
            reviewIssue.setTaskId(taskId);
            reviewIssue.setFilePath(StringUtils.hasText(issue.getFilePath()) ? issue.getFilePath() : defaultFilePath);
            reviewIssue.setLineNumber(issue.getLineNumber());
            reviewIssue.setIssueType(issue.getIssueType());
            reviewIssue.setIssueTypeZh(issue.getIssueTypeZh());
            reviewIssue.setSeverity(normalizeSeverity(issue.getSeverity()));
            reviewIssue.setTitle(issue.getTitle());
            reviewIssue.setDescription(issue.getDescription());
            reviewIssue.setSuggestion(issue.getSuggestion());
            reviewIssue.setSource(normalizeSource(issue.getSource()));
            reviewIssue.setRuleReference(issue.getRuleReference());
            reviewIssue.setCreatedAt(LocalDateTime.now());
            reviewIssues.add(reviewIssue);
        }
        return reviewIssues;
    }

    public String calculateRiskLevel(List<ReviewIssue> reviewIssues) {
        return reviewIssues.stream()
                .map(ReviewIssue::getSeverity)
                .filter(StringUtils::hasText)
                .map(this::normalizeSeverity)
                .min(Comparator.comparingInt(this::severityRank))
                .orElse("PASS");
    }

    private String normalizeSource(String source) {
        if (!StringUtils.hasText(source)) {
            return "LLM";
        }
        String normalizedSource = source.trim().toUpperCase(Locale.ROOT);
        return "TOOL".equals(normalizedSource) ? "TOOL" : "LLM";
    }

    private String normalizeSeverity(String severity) {
        if (!StringUtils.hasText(severity)) {
            return "LOW";
        }
        return severity.trim().toUpperCase(Locale.ROOT);
    }

    private int severityRank(String severity) {
        return switch (severity) {
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            case "LOW" -> 3;
            default -> 4;
        };
    }
}
