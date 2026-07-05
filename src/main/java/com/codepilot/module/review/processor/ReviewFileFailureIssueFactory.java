package com.codepilot.module.review.processor;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class ReviewFileFailureIssueFactory {

    public ReviewIssue create(Long taskId, ReviewFile reviewFile, Exception exception) {
        ReviewIssue issue = new ReviewIssue();
        issue.setTaskId(taskId);
        issue.setFilePath(reviewFile.getFilePath());
        issue.setIssueType("AI_REVIEW_FAILED");
        issue.setIssueTypeZh("AI review failed");
        issue.setSeverity("MEDIUM");
        issue.setTitle("AI review failed for this file");
        issue.setDescription("This file could not be reviewed by the AI pipeline: "
                + failureMessage(exception));
        issue.setSuggestion("Retry the review after checking LLM availability, prompt/schema compatibility, and logs.");
        issue.setSource("SYSTEM");
        issue.setCreatedAt(LocalDateTime.now());
        return issue;
    }

    public String failureMessage(Exception exception) {
        Throwable rootCause = rootCause(exception);
        String message = rootCause == null ? null : rootCause.getMessage();
        if (message == null && exception != null) {
            message = exception.getMessage();
        }
        return SensitiveDataSanitizer.redactAndTruncate(message, 500);
    }

    private Throwable rootCause(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
