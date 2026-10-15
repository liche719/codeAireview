package com.codepilot.module.review.service.impl;

import com.codepilot.common.util.MarkdownSanitizer;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.memory.ReviewFindingFingerprint;
import com.codepilot.module.review.report.ReviewIssueEvidenceFormatter;
import org.springframework.util.StringUtils;

final class GitHubInlineCommentBodyBuilder {

    private static final int MAX_TEXT_LENGTH = 500;

    private static final String INLINE_MARKER = "<!-- codepilot-inline-review -->";

    /** 稳定的 finding 身份标记，供跨 headSha 的线程回读使用（R7 误报抑制）。 */
    static final String FINDING_MARKER_PREFIX = "<!-- codepilot-finding:";

    String build(ReviewIssue issue, String fingerprint) {
        StringBuilder body = new StringBuilder();
        body.append(INLINE_MARKER).append("\n\n");
        body.append("<!-- codepilot-inline-review:").append(fingerprint).append(" -->").append("\n\n");
        // 上面那个指纹含 headSha 与行号，跨 push 会变，只适合做"同一 commit 内去重"。
        // 这里再嵌一个**不含行号**的稳定指纹，让 R7 能把被 resolve 的线程对应回具体 finding。
        String findingFingerprint = ReviewFindingFingerprint.findingFingerprint(issue);
        if (StringUtils.hasText(findingFingerprint)) {
            body.append(FINDING_MARKER_PREFIX).append(findingFingerprint).append(" -->").append("\n\n");
        }
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
