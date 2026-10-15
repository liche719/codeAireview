package com.codepilot.module.review.service.impl;

import com.codepilot.common.util.MarkdownSanitizer;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.memory.ReviewFindingFingerprint;
import com.codepilot.module.review.report.ReviewIssueEvidenceFormatter;
import org.springframework.util.StringUtils;

final class GitHubInlineCommentBodyBuilder {

    private static final int MAX_TEXT_LENGTH = 500;

    /** 建议代码块的上限。比正文大得多——代码被截断就不可应用了。 */
    private static final int MAX_CODE_LENGTH = 20000;

    private static final String INLINE_MARKER = "<!-- codepilot-inline-review -->";

    /** 稳定的 finding 身份标记，供跨 headSha 的线程回读使用（R7 误报抑制）。 */
    static final String FINDING_MARKER_PREFIX = "<!-- codepilot-finding:";

    String build(ReviewIssue issue, String fingerprint) {
        return build(issue, fingerprint, false, true);
    }

    String build(ReviewIssue issue, String fingerprint, boolean suggestionApplicable) {
        return build(issue, fingerprint, suggestionApplicable, true);
    }

    /**
     * @param suggestionApplicable 服务端是否判定该建议可以一键应用（见 {@code ReviewSuggestionValidator}）
     * @param fallbackToDiff       不可一键应用时，是否仍把建议代码以 {@code diff} 块展示；
     *                             为 false 则连代码块一起省略
     */
    String build(ReviewIssue issue, String fingerprint, boolean suggestionApplicable, boolean fallbackToDiff) {
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
        appendSuggestedCode(body, issue.getSuggestedCode(), suggestionApplicable, fallbackToDiff);
        return body.toString();
    }

    /**
     * 追加建议代码块。
     *
     * <p>只有服务端校验通过时才用 {@code ```suggestion}——GitHub 只认这个围栏才会给出一键应用按钮。
     * 校验不通过时<b>换成 {@code ```diff}</b>（PR-Agent 的同一做法）：保留多行展示，
     * 但明确不是可一键应用的建议。</p>
     *
     * <p>并且会加一句说明，避免用户看到一个既叫 diff、内容又不是标准 diff 的块而困惑。</p>
     *
     * <p>注意 {@code diff} 围栏里的内容其实是<b>替换后的整段代码</b>而非标准 diff：
     * 这是刻意为之——GitHub 只对 {@code suggestion} 围栏做行级替换，
     * 其余围栏一律按普通代码渲染，所以不可能被误点。</p>
     */
    private void appendSuggestedCode(StringBuilder body, String suggestedCode,
                                     boolean suggestionApplicable, boolean fallbackToDiff) {
        if (!StringUtils.hasText(suggestedCode)) {
            return;
        }
        if (!suggestionApplicable && !fallbackToDiff) {
            return;
        }
        String code = MarkdownSanitizer.sanitizeCodeBlockText(suggestedCode, MAX_CODE_LENGTH, "");
        if (!StringUtils.hasText(code)) {
            return;
        }
        if (suggestionApplicable) {
            body.append("\n```suggestion\n").append(code).append("\n```\n");
            return;
        }
        body.append("\nSuggested replacement (not one-click applicable; the target lines are not a contiguous\n")
                .append("block on the new side of the diff):\n");
        body.append("```diff\n").append(code).append("\n```\n");
    }

    private String sanitizeIssueText(String content) {
        return MarkdownSanitizer.sanitizeInlineText(content, MAX_TEXT_LENGTH, "N/A");
    }
}
