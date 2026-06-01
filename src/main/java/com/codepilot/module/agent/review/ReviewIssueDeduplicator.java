package com.codepilot.module.agent.review;

import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.review.dedupe.ReviewIssueDuplicateKey;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ReviewIssueDeduplicator {

    public List<AiReviewIssue> dedupe(List<AiReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        Map<String, AiReviewIssue> winners = new LinkedHashMap<>();
        for (AiReviewIssue issue : issues) {
            if (issue == null) {
                continue;
            }
            String key = ReviewIssueDuplicateKey.key(
                    issue.getFilePath(),
                    issue.getLineNumber(),
                    issue.getIssueType(),
                    issue.getIssueTypeZh(),
                    issue.getTitle(),
                    issue.getDescription(),
                    issue.getSuggestion()
            );
            winners.merge(key, issue, this::betterIssue);
        }
        return new ArrayList<>(winners.values());
    }

    private AiReviewIssue betterIssue(AiReviewIssue current, AiReviewIssue candidate) {
        return score(candidate) > score(current) ? candidate : current;
    }

    private int score(AiReviewIssue issue) {
        return severityScore(issue)
                + sourceScore(issue)
                + groundingScore(issue)
                + completenessScore(issue);
    }

    private int severityScore(AiReviewIssue issue) {
        return switch (normalize(issue == null ? null : issue.getSeverity())) {
            case "HIGH" -> 60;
            case "MEDIUM" -> 40;
            case "LOW" -> 20;
            default -> 10;
        };
    }

    private int sourceScore(AiReviewIssue issue) {
        return switch (normalize(issue == null ? null : issue.getSource())) {
            case "TOOL" -> 30;
            case "SYSTEM" -> 20;
            case "LLM" -> 12;
            default -> 8;
        };
    }

    private int groundingScore(AiReviewIssue issue) {
        String ruleReference = normalize(issue == null ? null : issue.getRuleReference());
        if (ruleReference.contains("PATCH_VERIFIED:PATCH_LINE")) {
            return 18;
        }
        if (ruleReference.contains("PATCH_VERIFIED:PATCH_TEXT")) {
            return 14;
        }
        if ("TOOL".equals(normalize(issue == null ? null : issue.getSource()))) {
            return 8;
        }
        return 0;
    }

    private int completenessScore(AiReviewIssue issue) {
        int score = 0;
        if (StringUtils.hasText(issue == null ? null : issue.getDescription())) {
            score += Math.min(10, issue.getDescription().length() / 80);
        }
        if (StringUtils.hasText(issue == null ? null : issue.getSuggestion())) {
            score += Math.min(10, issue.getSuggestion().length() / 80);
        }
        return score;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }
}
