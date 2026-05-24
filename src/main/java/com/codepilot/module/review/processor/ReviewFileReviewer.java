package com.codepilot.module.review.processor;

import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.dto.AiReviewRequest;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.review.assembler.ReviewIssueAssembler;
import com.codepilot.module.review.context.ReviewContext;
import com.codepilot.module.review.context.ReviewContextBuilder;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewFileReviewer {

    private final AiReviewService aiReviewService;

    private final ReviewIssueAssembler reviewIssueAssembler;

    private final ReviewContextBuilder reviewContextBuilder;

    public List<ReviewIssue> review(Long taskId, List<ReviewFile> reviewFiles) {
        if (reviewFiles == null || reviewFiles.isEmpty()) {
            return List.of();
        }
        ReviewContext reviewContext = reviewContextBuilder.build(reviewFiles);
        List<ReviewIssue> reviewIssues = new ArrayList<>();
        int reviewableFileCount = 0;
        int failedFileCount = 0;
        Exception firstFailure = null;
        for (ReviewFile reviewFile : reviewFiles) {
            if (!Boolean.TRUE.equals(reviewFile.getSkipped())) {
                reviewableFileCount++;
                try {
                    reviewIssues.addAll(reviewFileWithAi(taskId, reviewFile, reviewContext));
                } catch (Exception exception) {
                    failedFileCount++;
                    if (firstFailure == null) {
                        firstFailure = exception;
                    }
                    log.warn("AI review failed for file but review task will continue, taskId={}, filePath={}, errorType={}, message={}",
                            taskId,
                            reviewFile.getFilePath(),
                            exception.getClass().getSimpleName(),
                            SensitiveDataSanitizer.redact(exception.getMessage()));
                    reviewIssues.add(failureIssue(taskId, reviewFile, exception));
                }
            }
        }
        if (reviewableFileCount > 0 && failedFileCount == reviewableFileCount) {
            throw new IllegalStateException("AI review failed for all reviewable files, failedCount=" + failedFileCount
                    + ", firstError=" + failureMessage(firstFailure),
                    firstFailure);
        }
        return reviewIssues;
    }

    private List<ReviewIssue> reviewFileWithAi(Long taskId, ReviewFile reviewFile, ReviewContext reviewContext) {
        try {
            AiReviewResult aiReviewResult = aiReviewService.reviewFile(new AiReviewRequest(
                    taskId,
                    reviewFile.getFilePath(),
                    reviewFile.getPatch(),
                    reviewContext.toAiReviewContext()
            ));
            return reviewIssueAssembler.toReviewIssues(taskId, reviewFile.getFilePath(), aiReviewResult);
        } catch (Exception exception) {
            throw new IllegalStateException("AI review failed for file " + reviewFile.getFilePath(), exception);
        }
    }

    private ReviewIssue failureIssue(Long taskId, ReviewFile reviewFile, Exception exception) {
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

    private String failureMessage(Exception exception) {
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
