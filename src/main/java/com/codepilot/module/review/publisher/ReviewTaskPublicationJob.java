package com.codepilot.module.review.publisher;

import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "codepilot.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ReviewTaskPublicationJob {

    private final ReviewTaskMapper reviewTaskMapper;

    private final ReviewTaskPublicationService publicationService;

    private final ReviewProperties reviewProperties;

    @Scheduled(fixedDelayString = "${codepilot.review.publication-poll-interval-millis:5000}")
    public void publishPendingReviews() {
        LocalDateTime now = LocalDateTime.now();
        reviewTaskMapper.findPublicationCandidates(
                        now,
                        now.minus(reviewProperties.getOutboxLockTimeout()),
                        Math.max(1, reviewProperties.getOutboxBatchSize())
                )
                .forEach(publicationService::publish);
    }
}
