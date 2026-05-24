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
            clearLineNumber(issue, "line is not a commentable added diff line");
        }
    }

    private void clearLineNumber(ReviewIssue issue, String reason) {
        log.debug("Clear non-commentable review issue line number, filePath={}, lineNumber={}, issueType={}, reason={}",
                issue.getFilePath(), issue.getLineNumber(), issue.getIssueType(), reason);
        issue.setLineNumber(null);
    }
}
