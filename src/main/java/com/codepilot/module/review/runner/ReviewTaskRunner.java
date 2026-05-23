package com.codepilot.module.review.runner;

import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.failure.ReviewTaskFailureHandler;
import com.codepilot.module.review.failure.ReviewTaskFailureResult;
import com.codepilot.module.review.processor.ReviewTaskProcessingResult;
import com.codepilot.module.review.processor.ReviewTaskProcessor;
import com.codepilot.module.review.publisher.ReviewCommentPublisher;
import com.codepilot.module.review.state.ReviewTaskStateManager;
import com.codepilot.module.review.sync.ReviewTaskHeadShaRefresher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewTaskRunner {

    private final ReviewTaskFailureHandler reviewTaskFailureHandler;

    private final ReviewTaskHeadShaRefresher reviewTaskHeadShaRefresher;

    private final ReviewTaskProcessor reviewTaskProcessor;

    private final ReviewCommentPublisher reviewCommentPublisher;

    private final ReviewTaskStateManager reviewTaskStateManager;

    public void run(ReviewTask task) {
        Long taskId = task.getId();
        reviewTaskStateManager.markRunning(task);

        try {
            reviewTaskHeadShaRefresher.refresh(task);
            ReviewTaskProcessingResult processingResult = reviewTaskProcessor.process(task);

            reviewTaskStateManager.markSuccess(
                    task,
                    processingResult.totalFiles(),
                    processingResult.totalIssues(),
                    processingResult.riskLevel()
            );
            log.info("Review task processed successfully, taskId={}, totalFiles={}, totalIssues={}",
                    taskId, processingResult.totalFiles(), processingResult.totalIssues());
            reviewCommentPublisher.publish(task);
        } catch (Exception exception) {
            ReviewTaskFailureResult failureResult = reviewTaskFailureHandler.handle(task, exception);
            throw new IllegalStateException("review task failed, taskId=" + taskId
                    + ", errorType=" + failureResult.errorType());
        }
    }
}
