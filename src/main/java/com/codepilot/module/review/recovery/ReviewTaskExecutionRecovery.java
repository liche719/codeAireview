package com.codepilot.module.review.recovery;

import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.queue.ReviewTaskMessageDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "codepilot.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ReviewTaskExecutionRecovery {

    private final ReviewTaskMapper reviewTaskMapper;

    private final ReviewTaskMessageDispatcher reviewTaskMessageDispatcher;

    private final ReviewProperties reviewProperties;

    @Scheduled(fixedDelayString = "${codepilot.review.execution-recovery-interval-millis:30000}")
    @Transactional(rollbackFor = Exception.class)
    public void recoverExpiredExecutions() {
        LocalDateTime now = LocalDateTime.now();
        List<ReviewTask> expiredTasks = reviewTaskMapper.findExpiredExecutions(
                now,
                Math.max(1, reviewProperties.getOutboxBatchSize())
        );
        for (ReviewTask task : expiredTasks) {
            recover(task, now);
        }
    }

    public void recover(ReviewTask task, LocalDateTime now) {
        int recovered = reviewTaskMapper.recoverExpiredExecution(
                task.getId(), task.getExecutionToken(), now
        );
        if (recovered == 0) {
            return;
        }
        reviewTaskMessageDispatcher.enqueue(task.getId());
        log.warn("Recovered expired review task execution, taskId={}, previousExecutionToken={}",
                task.getId(), task.getExecutionToken());
    }
}
