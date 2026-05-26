package com.codepilot.module.review.assembler;

import com.codepilot.common.util.SensitiveDataSanitizer;
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
import java.util.Set;

@Component
public class ReviewIssueAssembler {

    private static final int MAX_TITLE_LENGTH = 255;

    private static final int MAX_TEXT_LENGTH = 2000;

    private static final int MAX_RULE_REFERENCE_LENGTH = 255;

    private static final int MIN_DESCRIPTION_LENGTH = 12;

    private static final int MIN_SUGGESTION_LENGTH = 8;

    private static final Set<String> SUPPORTED_ISSUE_TYPES = Set.of(
            "BUG_RISK",
            "SECURITY",
            "PERFORMANCE",
            "STYLE",
            "SQL_RISK",
            "EXCEPTION_HANDLING",
            "LOGGING",
            "TEST_MISSING",
            "AI_REVIEW_FAILED"
    );

    private static final Set<String> WEAK_LLM_TITLES = Set.of(
            "LGTM",
            "NIT",
            "NITPICK",
            "STYLE",
            "MINOR",
            "SUGGESTION",
            "NO ISSUE",
            "NO ISSUES"
    );

    public List<ReviewIssue> toReviewIssues(Long taskId, String defaultFilePath, AiReviewResult aiReviewResult) {
        if (aiReviewResult == null || aiReviewResult.getIssues() == null || aiReviewResult.getIssues().isEmpty()) {
            return List.of();
        }

        List<ReviewIssue> reviewIssues = new ArrayList<>();
        for (AiReviewIssue issue : aiReviewResult.getIssues()) {
            if (!shouldKeep(issue)) {
                continue;
            }
            String normalizedSource = normalizeSource(issue.getSource());
            ReviewIssue reviewIssue = new ReviewIssue();
            reviewIssue.setTaskId(taskId);
            reviewIssue.setFilePath(resolveFilePath(defaultFilePath, issue.getFilePath()));
            reviewIssue.setLineNumber(issue.getLineNumber());
            reviewIssue.setIssueType(normalizeIssueType(issue.getIssueType()));
            reviewIssue.setIssueTypeZh(cleanText(issue.getIssueTypeZh(), 64));
            reviewIssue.setSeverity(normalizeSeverity(issue.getSeverity()));
            reviewIssue.setTitle(cleanText(issue.getTitle(), MAX_TITLE_LENGTH));
            reviewIssue.setDescription(cleanText(issue.getDescription(), MAX_TEXT_LENGTH));
            reviewIssue.setSuggestion(cleanText(issue.getSuggestion(), MAX_TEXT_LENGTH));
            reviewIssue.setSource(normalizedSource);
            reviewIssue.setRuleReference(cleanText(issue.getRuleReference(), MAX_RULE_REFERENCE_LENGTH));
            reviewIssue.setFinalScore(0);
            reviewIssue.setPublishDecision("PUBLISH");
            reviewIssue.setCommentChannel("INLINE");
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

    private boolean shouldKeep(AiReviewIssue issue) {
        if (issue == null) {
            return false;
        }
        String source = normalizeSource(issue.getSource());
        if ("TOOL".equals(source)) {
            return hasPublishableRequiredFields(issue);
        }
        return hasPublishableRequiredFields(issue) && isActionableLlmIssue(issue);
    }

    private boolean hasPublishableRequiredFields(AiReviewIssue issue) {
        return StringUtils.hasText(issue.getIssueType())
                && StringUtils.hasText(issue.getSeverity())
                && StringUtils.hasText(issue.getTitle())
                && StringUtils.hasText(issue.getDescription())
                && StringUtils.hasText(issue.getSuggestion());
    }

    private boolean isActionableLlmIssue(AiReviewIssue issue) {
        if (!SUPPORTED_ISSUE_TYPES.contains(normalizeIssueType(issue.getIssueType()))) {
            return false;
        }
        String severity = normalizeSeverity(issue.getSeverity());
        String normalizedTitle = normalizeForQualityCheck(issue.getTitle());
        if ("LOW".equals(severity) && WEAK_LLM_TITLES.contains(normalizedTitle)) {
            return false;
        }
        String description = normalizeForQualityCheck(issue.getDescription());
        String suggestion = normalizeForQualityCheck(issue.getSuggestion());
        if (description.length() < MIN_DESCRIPTION_LENGTH || suggestion.length() < MIN_SUGGESTION_LENGTH) {
            return false;
        }
        return !description.equals(suggestion);
    }

    private String normalizeIssueType(String issueType) {
        String normalized = StringUtils.hasText(issueType) ? issueType.trim().toUpperCase(Locale.ROOT) : "BUG_RISK";
        return SUPPORTED_ISSUE_TYPES.contains(normalized) ? normalized : "BUG_RISK";
    }

    private String resolveFilePath(String defaultFilePath, String modelFilePath) {
        if (StringUtils.hasText(defaultFilePath)) {
            return defaultFilePath;
        }
        return StringUtils.hasText(modelFilePath) ? modelFilePath : null;
    }

    private String normalizeSeverity(String severity) {
        if (!StringUtils.hasText(severity)) {
            return "LOW";
        }
        String normalized = severity.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "HIGH", "MEDIUM", "LOW" -> normalized;
            default -> "LOW";
        };
    }

    private int severityRank(String severity) {
        return switch (severity) {
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            case "LOW" -> 3;
            default -> 4;
        };
    }

    private String cleanText(String content, int maxLength) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String compact = SensitiveDataSanitizer.redact(content)
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (compact.length() > maxLength) {
            return SensitiveDataSanitizer.truncatePreservingRedactionMarker(compact, maxLength);
        }
        return compact;
    }

    private String normalizeForQualityCheck(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String normalized = SensitiveDataSanitizer.redact(content)
                .replaceAll("\\s+", " ")
                .trim()
                .toUpperCase(Locale.ROOT);
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
