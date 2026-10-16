package com.codepilot.module.review.processor;

import com.codepilot.module.review.diff.DiffLineMapper;
import com.codepilot.module.review.diff.DiffLineMapping;
import com.codepilot.module.review.entity.ReviewIssue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewIssueLocationGuard {

    /**
     * 行号落在 diff 之外时的原因。
     *
     * <p>文案必须与 {@link DiffLineMapper#map} 的<b>实际</b>判定范围一致：它同时接受
     * 新增行（RIGHT）、删除行（LEFT）与上下文行（RIGHT）。早先这里写的是
     * {@code "line is not a commentable added diff line"}，只提"新增行"，
     * 让人以为删除行与上下文行也会被拒——而测试
     * {@code shouldKeepLineNumberWhenIssueTargetsContextOrDeletedLine} 证明它们是被保留的。
     * 误导性文案最容易被读成"实现就是这样"。</p>
     */
    static final String REASON_NOT_IN_DIFF =
            "line is not part of the diff for this file (no matching added, deleted or context line)";

    private final DiffLineMapper diffLineMapper;

    public List<ReviewIssue> keepOnlyCommentableChangedLines(
            String reviewedFilePath,
            String patch,
            List<ReviewIssue> issues
    ) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        for (ReviewIssue issue : issues) {
            normalizeLocation(reviewedFilePath, patch, issue);
        }
        return issues;
    }

    private void normalizeLocation(String reviewedFilePath, String patch, ReviewIssue issue) {
        if (issue == null || issue.getLineNumber() == null) {
            return;
        }
        if (!StringUtils.hasText(reviewedFilePath)
                || !StringUtils.hasText(issue.getFilePath())
                || !reviewedFilePath.equals(issue.getFilePath())
                || !StringUtils.hasText(patch)) {
            clearLineNumber(issue, "missing or mismatched file context");
            return;
        }

        DiffLineMapping mapping = diffLineMapper.map(issue.getFilePath(), patch, issue.getLineNumber());
        if (!mapping.commentable()) {
            clearLineNumber(issue, REASON_NOT_IN_DIFF);
        }
    }

    private void clearLineNumber(ReviewIssue issue, String reason) {
        log.debug("Clear non-commentable review issue line number, filePath={}, lineNumber={}, issueType={}, reason={}",
                issue.getFilePath(), issue.getLineNumber(), issue.getIssueType(), reason);
        issue.setLineNumber(null);
    }
}
