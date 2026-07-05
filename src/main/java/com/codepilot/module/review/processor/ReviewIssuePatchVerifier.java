package com.codepilot.module.review.processor;

import com.codepilot.module.review.context.ReviewContext;
import com.codepilot.module.review.diff.DiffLineMapper;
import com.codepilot.module.review.diff.DiffLineMapping;
import com.codepilot.module.review.entity.ReviewIssue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewIssuePatchVerifier {

    private static final int MAX_RULE_REFERENCE_LENGTH = 255;

    private static final int MAX_DESCRIPTION_LENGTH = 2000;

    private final DiffLineMapper diffLineMapper;

    public List<ReviewIssue> keepVerified(
            String reviewedFilePath,
            String patch,
            ReviewContext reviewContext,
            List<ReviewIssue> issues
    ) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        ReviewIssuePatchEvidence patchEvidence = ReviewIssuePatchEvidence.from(patch);
        if (!patchEvidence.hasHunk()) {
            return issues;
        }

        ReviewIssuePlanEvidence planEvidence = ReviewIssuePlanEvidence.from(reviewedFilePath, reviewContext);
        List<ReviewIssue> verifiedIssues = new ArrayList<>();
        int droppedCount = 0;
        for (ReviewIssue issue : issues) {
            ReviewIssueVerificationResult result =
                    verifyIssue(reviewedFilePath, patch, patchEvidence, planEvidence, issue);
            if (result.keep()) {
                annotateIssue(issue, result);
                verifiedIssues.add(issue);
            } else {
                droppedCount++;
                log.debug("Drop ungrounded AI review issue, filePath={}, issueType={}, severity={}, title={}",
                        issue == null ? null : issue.getFilePath(),
                        issue == null ? null : issue.getIssueType(),
                        issue == null ? null : issue.getSeverity(),
                        issue == null ? null : issue.getTitle());
            }
        }
        if (droppedCount > 0) {
            log.info("Patch verification dropped ungrounded AI review issue(s), filePath={}, droppedCount={}, keptCount={}",
                    reviewedFilePath, droppedCount, verifiedIssues.size());
        }
        return verifiedIssues;
    }

    private ReviewIssueVerificationResult verifyIssue(
            String reviewedFilePath,
            String patch,
            ReviewIssuePatchEvidence patchEvidence,
            ReviewIssuePlanEvidence planEvidence,
            ReviewIssue issue
    ) {
        if (issue == null) {
            return ReviewIssueVerificationResult.drop();
        }
        String source = normalize(issue.getSource());
        if ("TOOL".equals(source) || "SYSTEM".equals(source)) {
            return ReviewIssueVerificationResult.keep("DETERMINISTIC_SOURCE", "deterministic source");
        }
        if (!StringUtils.hasText(reviewedFilePath)
                || !reviewedFilePath.equals(issue.getFilePath())) {
            return ReviewIssueVerificationResult.drop();
        }

        DiffLineMapping mapping = issue.getLineNumber() == null
                ? DiffLineMapping.notCommentable()
                : diffLineMapper.map(issue.getFilePath(), patch, issue.getLineNumber());
        if (mapping.commentable()) {
            return ReviewIssueVerificationResult.keep(
                    "PATCH_LINE",
                    "changed line " + mapping.line() + " on " + mapping.side()
            );
        }

        Set<String> issueTokens = ReviewIssueTextTokens.tokens(issueText(issue));
        int patchOverlap = overlapCount(issueTokens, patchEvidence.tokens());
        if (patchOverlap >= 2) {
            return ReviewIssueVerificationResult.keep("PATCH_TEXT", "matched changed patch tokens");
        }

        int planOverlap = overlapCount(issueTokens, planEvidence.tokens());
        if (planEvidence.alignsWithIssue(issue) && planOverlap >= 1) {
            return ReviewIssueVerificationResult.keep("REVIEW_PLAN", "matched deterministic review plan risk");
        }

        if (isHighSignalIssueType(issue) && planEvidence.alignsWithIssue(issue) && patchEvidence.pathRiskAligned(issue)) {
            return ReviewIssueVerificationResult.keep("PATCH_RISK_AREA", "matched changed file risk area");
        }

        return ReviewIssueVerificationResult.drop();
    }

    private void annotateIssue(ReviewIssue issue, ReviewIssueVerificationResult result) {
        if (issue == null || !"LLM".equals(normalize(issue.getSource())) || !"KEEP".equals(result.action())) {
            return;
        }
        String evidence = "PATCH_VERIFIED:" + result.reasonCode();
        issue.setRuleReference(appendBounded(issue.getRuleReference(), evidence, MAX_RULE_REFERENCE_LENGTH));
        String descriptionEvidence = "Evidence: " + result.explanation() + ".";
        if (!containsIgnoreCase(issue.getDescription(), "Evidence:")) {
            issue.setDescription(appendBounded(issue.getDescription(), descriptionEvidence, MAX_DESCRIPTION_LENGTH));
        }
    }

    private boolean isHighSignalIssueType(ReviewIssue issue) {
        return switch (normalize(issue.getIssueType())) {
            case "SECURITY", "SQL_RISK", "TEST_MISSING", "EXCEPTION_HANDLING", "LOGGING" -> true;
            default -> false;
        };
    }

    private String issueText(ReviewIssue issue) {
        return String.join(" ",
                nullToEmpty(issue.getIssueType()),
                nullToEmpty(issue.getTitle()),
                nullToEmpty(issue.getDescription()),
                nullToEmpty(issue.getSuggestion()),
                nullToEmpty(issue.getRuleReference()));
    }

    private int overlapCount(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String token : left) {
            if (right.contains(token)) {
                count++;
            }
        }
        return count;
    }

    private String appendBounded(String existing, String addition, int limit) {
        String merged = StringUtils.hasText(existing)
                ? existing.trim() + " | " + addition
                : addition;
        if (merged.length() <= limit) {
            return merged;
        }
        return merged.substring(0, Math.max(0, limit - 3)).trim() + "...";
    }

    private boolean containsIgnoreCase(String text, String needle) {
        return StringUtils.hasText(text)
                && StringUtils.hasText(needle)
                && text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
