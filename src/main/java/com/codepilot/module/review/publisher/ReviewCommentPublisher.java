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
        if (reviewCommentMode.includesInline() && reviewCommentMode.includesSummary()) {
            return publishInlineAndSummary(task);
        }
        if (reviewCommentMode.includesInline()) {
            return publishInlineOrFallbackToSummary(task);
        }
        return publishSummary(task);
    }

    /**
     * BOTH：先发行内，再发摘要，**两者互不掩盖**。
     *
     * <p>顺序是有意的：行内评论定位到具体行，摘要给总览与覆盖度声明。
     * 摘要必须**无条件**尝试——它承载 R2 的"本次只审了 N/M 个文件"，
     * 一旦因为行内成功就跳过摘要，用户会把"部分覆盖"读成"全部通过"。</p>
     *
     * <p>返回值语义：只要**任一**通道成功就算发布成功（避免因为一个通道的
     * 权限/定位问题把另一个已经成功的通道回滚成失败重试，导致重复评论）。</p>
     */
    private ReviewCommentPublishResult publishInlineAndSummary(ReviewTask task) {
        boolean inlineSucceeded = false;
        try {
            GitHubInlineCommentResult inlineResult = gitHubInlineCommentService.commentInlineIssues(task.getId());
            inlineSucceeded = inlineResult != null && inlineResult.hasSuccess();
        } catch (Exception exception) {
            log.warn("GitHub PR inline comment failed unexpectedly, taskId={}, errorType={}, message={}",
                    task.getId(), exception.getClass().getSimpleName(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
        }

        ReviewCommentPublishResult summaryResult = publishSummary(task);
        if (inlineSucceeded || summaryResult.published()) {
            return ReviewCommentPublishResult.publishedResult();
        }
        return summaryResult;
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
