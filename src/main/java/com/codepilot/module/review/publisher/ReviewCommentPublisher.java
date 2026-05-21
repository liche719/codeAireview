package com.codepilot.module.review.publisher;

import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.service.GitHubCommentService;
import com.codepilot.module.review.service.GitHubInlineCommentResult;
import com.codepilot.module.review.service.GitHubInlineCommentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewCommentPublisher {

    private final GitHubCommentService githubCommentService;

    private final GitHubInlineCommentService gitHubInlineCommentService;

    public void publish(ReviewTask task) {
        ReviewCommentMode reviewCommentMode = ReviewCommentMode.fromValue(task.getReviewCommentMode());
        if (reviewCommentMode.isInlineOnly()) {
            publishInlineOrFallbackToSummary(task);
            return;
        }
        publishSummary(task);
    }

    private void publishInlineOrFallbackToSummary(ReviewTask task) {
        GitHubInlineCommentResult result = null;
        try {
            result = gitHubInlineCommentService.commentInlineIssues(task.getId());
        } catch (Exception exception) {
            log.warn("GitHub PR inline comment failed unexpectedly, taskId={}, errorType={}, message={}",
                    task.getId(), exception.getClass().getSimpleName(), SensitiveDataSanitizer.redact(exception.getMessage()));
        }
        if (result == null || !result.hasSuccess()) {
            publishSummary(task);
        }
    }

    private void publishSummary(ReviewTask task) {
        try {
            githubCommentService.commentReviewResult(task.getId());
        } catch (Exception exception) {
            log.warn("GitHub PR summary comment failed unexpectedly, taskId={}, errorType={}, message={}",
                    task.getId(), exception.getClass().getSimpleName(), SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }
}
