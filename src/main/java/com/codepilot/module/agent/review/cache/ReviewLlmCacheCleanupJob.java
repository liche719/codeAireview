package com.codepilot.module.agent.review.cache;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.infrastructure.llm.LlmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewLlmCacheCleanupJob {

    private final LlmProperties llmProperties;

    private final ReviewLlmCache reviewLlmCache;

    @Scheduled(cron = "${codepilot.llm.review-cache-cleanup-cron:0 0 */6 * * *}")
    public void cleanupExpiredEntries() {
        if (llmProperties == null || !llmProperties.isReviewCacheCleanupEnabled()) {
            return;
        }
        try {
            int deletedCount = reviewLlmCache.evictExpired();
            if (deletedCount > 0) {
                log.info("AI review cache cleanup deleted expired entries, deletedCount={}", deletedCount);
            }
        } catch (Exception exception) {
            log.warn("AI review cache cleanup failed, errorType={}, message={}",
                    exception.getClass().getSimpleName(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }
}
