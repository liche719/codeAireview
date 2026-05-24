package com.codepilot.module.agent.review.cache;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.review.ReviewLlmInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewLlmCache {

    private final LlmProperties llmProperties;

    private final AiReviewCacheMapper aiReviewCacheMapper;

    private final ObjectMapper objectMapper;

    private final ReviewLlmCacheKeyBuilder reviewLlmCacheKeyBuilder;

    public Optional<AiReviewResult> find(String providerName, ReviewLlmInput input) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        try {
            ReviewLlmCacheKey cacheKey = reviewLlmCacheKeyBuilder.build(providerName, input);
            AiReviewCacheEntry entry = aiReviewCacheMapper.findFreshByCacheKey(
                    cacheKey.value(),
                    LocalDateTime.now().minusDays(Math.max(1, llmProperties.getReviewCacheTtlDays()))
            );
            if (entry == null || !StringUtils.hasText(entry.getResultJson())) {
                return Optional.empty();
            }
            AiReviewResult result = objectMapper.readValue(entry.getResultJson(), AiReviewResult.class);
            updateLastUsedAt(entry);
            log.info("AI review cache hit, filePath={}, provider={}, model={}",
                    input == null ? null : input.filePath(), cacheKey.provider(), cacheKey.modelName());
            return Optional.of(result);
        } catch (Exception exception) {
            log.warn("AI review cache lookup failed, filePath={}, errorType={}, message={}",
                    input == null ? null : input.filePath(),
                    exception.getClass().getSimpleName(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
            return Optional.empty();
        }
    }

    private void updateLastUsedAt(AiReviewCacheEntry entry) {
        try {
            aiReviewCacheMapper.updateLastUsedAt(entry.getId(), LocalDateTime.now());
        } catch (Exception exception) {
            log.warn("AI review cache last-used update failed, cacheId={}, errorType={}, message={}",
                    entry == null ? null : entry.getId(),
                    exception.getClass().getSimpleName(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }

    public void save(String providerName, ReviewLlmInput input, AiReviewResult result) {
        if (!isEnabled() || result == null) {
            return;
        }
        try {
            ReviewLlmCacheKey cacheKey = reviewLlmCacheKeyBuilder.build(providerName, input);
            LocalDateTime now = LocalDateTime.now();
            AiReviewCacheEntry entry = new AiReviewCacheEntry();
            entry.setCacheKey(cacheKey.value());
            entry.setProvider(cacheKey.provider());
            entry.setModelName(cacheKey.modelName());
            entry.setPromptSignature(cacheKey.promptSignature());
            entry.setFilePath(input == null ? null : input.filePath());
            entry.setResultJson(objectMapper.writeValueAsString(result));
            entry.setCreatedAt(now);
            entry.setUpdatedAt(now);
            entry.setLastUsedAt(now);
            aiReviewCacheMapper.upsert(entry);
        } catch (Exception exception) {
            log.warn("AI review cache save failed, filePath={}, errorType={}, message={}",
                    input == null ? null : input.filePath(),
                    exception.getClass().getSimpleName(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }

    public int evictExpired() {
        if (!isEnabled()) {
            return 0;
        }
        LocalDateTime updatedBefore = LocalDateTime.now().minusDays(Math.max(1, llmProperties.getReviewCacheTtlDays()));
        return aiReviewCacheMapper.deleteExpired(updatedBefore);
    }

    private boolean isEnabled() {
        return llmProperties != null
                && llmProperties.isReviewCacheEnabled()
                && llmProperties.getReviewCacheTtlDays() > 0;
    }
}
