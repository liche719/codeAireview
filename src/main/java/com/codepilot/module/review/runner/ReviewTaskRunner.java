package com.codepilot.module.review.runner;

import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.failure.ReviewTaskFailureHandler;
import com.codepilot.module.review.failure.ReviewTaskFailureResult;
import com.codepilot.module.review.processor.ReviewTaskProcessingResult;
import com.codepilot.module.review.processor.ReviewTaskProcessor;
import com.codepilot.module.review.memory.ReviewDismissalService;
import com.codepilot.module.review.persistence.ReviewTaskResultStore;
import com.codepilot.module.review.metrics.ReviewMetrics;
import com.codepilot.module.review.publisher.ReviewTaskPublicationService;
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

    private final ReviewTaskResultStore reviewTaskResultStore;

    private final ReviewTaskPublicationService reviewTaskPublicationService;

    private final ReviewTaskStateManager reviewTaskStateManager;

    private final ReviewDismissalService reviewDismissalService;

    private final ReviewMetrics reviewMetrics;

    public ReviewTask claim(Long taskId) {
        return reviewTaskStateManager.claim(taskId);
    }

    public void run(ReviewTask task) {
        Long taskId = task.getId();
        long startNanos = System.nanoTime();
        try {
            reviewTaskHeadShaRefresher.refresh(task);
            // 在审查之前同步"被维护者 resolve 掉的线程"，这样记忆表里的 DISMISSED
            // 状态才能在随后的对账中被用上（从而不再重复提同一个问题）。
            // 该调用内部吞掉所有失败，不会影响审查本身。
            reviewDismissalService.syncResolvedThreads(task);
            ReviewTaskProcessingResult processingResult = reviewTaskProcessor.process(task);

            reviewTaskResultStore.replaceAndComplete(task, processingResult);
            log.info("Review task processed successfully, taskId={}, totalFiles={}, totalIssues={}",
                    taskId, processingResult.totalFiles(), processingResult.totalIssues());
            reviewMetrics.recordTaskExecution(startNanos, "success");
        } catch (Exception exception) {
            ReviewTaskFailureResult failureResult = reviewTaskFailureHandler.handle(task, exception);
            reviewMetrics.recordTaskExecution(startNanos, failureResult.finalAttempt() ? "failed" : "retry");
            throw new IllegalStateException("review task failed, taskId=" + taskId
                    + ", errorType=" + failureResult.errorType());
        }
        reviewTaskPublicationService.publish(taskId);
    }
}
