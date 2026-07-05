package com.codepilot.module.review.processor;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.review.config.ReviewFileExecutorConfig;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.context.ReviewContext;
import com.codepilot.module.review.entity.ReviewFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Component
public class ReviewFileReviewExecutor {

    private final ReviewSingleFileReviewer singleFileReviewer;

    private final ReviewFileFailureIssueFactory failureIssueFactory;

    private final ReviewProperties reviewProperties;

    private final Executor reviewFileExecutor;

    @Autowired
    public ReviewFileReviewExecutor(
            ReviewSingleFileReviewer singleFileReviewer,
            ReviewFileFailureIssueFactory failureIssueFactory,
            ReviewProperties reviewProperties,
            @Qualifier("reviewFileExecutor") Executor reviewFileExecutor
    ) {
        this.singleFileReviewer = singleFileReviewer;
        this.failureIssueFactory = failureIssueFactory;
        this.reviewProperties = reviewProperties;
        this.reviewFileExecutor = reviewFileExecutor;
    }

    public ReviewFileReviewExecutor(
            ReviewSingleFileReviewer singleFileReviewer,
            ReviewFileFailureIssueFactory failureIssueFactory,
            ReviewProperties reviewProperties
    ) {
        this(
                singleFileReviewer,
                failureIssueFactory,
                reviewProperties,
                ReviewFileExecutorConfig.newReviewFileExecutor(reviewProperties)
        );
    }

    public List<ReviewFileOutcome> reviewFiles(
            Long taskId,
            List<ReviewFile> reviewFiles,
            ReviewContext reviewContext
    ) {
        int parallelism = parallelism(reviewFiles.size());
        if (parallelism <= 1 || reviewFiles.size() <= 1) {
            return reviewFiles.stream()
                    .map(reviewFile -> reviewFileSafely(taskId, reviewFile, reviewContext))
                    .toList();
        }

        List<CompletableFuture<ReviewFileOutcome>> futures = reviewFiles.stream()
                .map(reviewFile -> CompletableFuture.supplyAsync(
                        () -> reviewFileSafely(taskId, reviewFile, reviewContext),
                        reviewFileExecutor
                ))
                .toList();
        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    private ReviewFileOutcome reviewFileSafely(Long taskId, ReviewFile reviewFile, ReviewContext reviewContext) {
        try {
            return new ReviewFileOutcome(singleFileReviewer.review(taskId, reviewFile, reviewContext), null);
        } catch (Exception exception) {
            log.warn("AI review failed for file but review task will continue, taskId={}, filePath={}, errorType={}, message={}",
                    taskId,
                    reviewFile.getFilePath(),
                    exception.getClass().getSimpleName(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
            return new ReviewFileOutcome(List.of(failureIssueFactory.create(taskId, reviewFile, exception)), exception);
        }
    }

    private int parallelism(int reviewableFileCount) {
        int configuredParallelism = reviewProperties == null ? 1 : reviewProperties.getMaxParallelFiles();
        return Math.min(Math.max(1, configuredParallelism), Math.max(1, reviewableFileCount));
    }
}
