package com.codepilot.module.review.service.impl;

import com.codepilot.common.util.MarkdownSanitizer;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.report.ReviewIssueEvidenceFormatter;
import org.springframework.util.StringUtils;

final class GitHubInlineCommentBodyBuilder {

    private static final int MAX_TEXT_LENGTH = 500;

    private static final String INLINE_MARKER = "<!-- codepilot-inline-review -->";

    String build(ReviewIssue issue, String fingerprint) {
        StringBuilder body = new StringBuilder();
        body.append(INLINE_MARKER).append("\n\n");
        body.append("<!-- codepilot-inline-review:").append(fingerprint).append(" -->").append("\n\n");
        body.append("Description:\n");
        body.append(sanitizeIssueText(issue.getDescription())).append("\n\n");
        String evidenceTrace = ReviewIssueEvidenceFormatter.compactTrace(issue);
        if (StringUtils.hasText(evidenceTrace)) {
            body.append("Evidence:\n");
            body.append(sanitizeIssueText(evidenceTrace)).append("\n\n");
        }
        body.append("Suggestion:\n");
        body.append(sanitizeIssueText(issue.getSuggestion())).append("\n");
        return body.toString();
    }

    private String sanitizeIssueText(String content) {
        return MarkdownSanitizer.sanitizeInlineText(content, MAX_TEXT_LENGTH, "N/A");
    }
}
