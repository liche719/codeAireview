package com.codepilot.module.review.publisher;

import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.service.GitHubCommentService;
import com.codepilot.module.review.service.GitHubCommentResult;
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

    public ReviewCommentPublishResult publish(ReviewTask task) {
        ReviewCommentMode reviewCommentMode = ReviewCommentMode.fromValue(task.getReviewCommentMode());
        if (reviewCommentMode.isInlineOnly()) {
            return publishInlineOrFallbackToSummary(task);
        }
        return publishSummary(task);
    }

    private ReviewCommentPublishResult publishInlineOrFallbackToSummary(ReviewTask task) {
        GitHubInlineCommentResult result = null;
        try {
            result = gitHubInlineCommentService.commentInlineIssues(task.getId());
        } catch (Exception exception) {
            log.warn("GitHub PR inline comment failed unexpectedly, taskId={}, errorType={}, message={}",
                    task.getId(), exception.getClass().getSimpleName(), SensitiveDataSanitizer.redact(exception.getMessage()));
        }
        if (result != null && result.hasSuccess()) {
            return ReviewCommentPublishResult.publishedResult();
        }
        return publishSummary(task);
    }

    private ReviewCommentPublishResult publishSummary(ReviewTask task) {
        try {
            GitHubCommentResult result = githubCommentService.commentReviewResult(task.getId());
            if (result == null) {
                return ReviewCommentPublishResult.failedResult("GitHub summary comment returned no result");
            }
            if (result.published()) {
                return ReviewCommentPublishResult.publishedResult();
            }
            if (result.skipped()) {
                return ReviewCommentPublishResult.skippedResult();
            }
            return ReviewCommentPublishResult.failedResult(result.errorMessage());
        } catch (Exception exception) {
            String errorMessage = SensitiveDataSanitizer.redactAndTruncate(exception.getMessage(), 2000);
            log.warn("GitHub PR summary comment failed unexpectedly, taskId={}, errorType={}, message={}",
                    task.getId(), exception.getClass().getSimpleName(), errorMessage);
            return ReviewCommentPublishResult.failedResult(errorMessage);
        }
    }
}
