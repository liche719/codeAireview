package com.codepilot.module.agent.service.impl;

import com.codepilot.module.agent.config.RagProperties;
import com.codepilot.module.agent.dto.ReviewRuleContext;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class ReviewRagCache {

    private final Map<String, CachedRuleContext> cache = new ConcurrentHashMap<>();

    List<ReviewRuleContext> get(String cacheKey, RagProperties properties) {
        if (!isCacheEnabled(properties) || !StringUtils.hasText(cacheKey)) {
            return null;
        }
        CachedRuleContext cached = cache.get(cacheKey);
        if (cached == null) {
            return null;
        }
        if (cached.isExpired(cacheTtl(properties))) {
            cache.remove(cacheKey, cached);
            return null;
        }
        cached.markAccessed();
        return copyContexts(cached.contexts());
    }

    void put(String cacheKey, List<ReviewRuleContext> contexts, RagProperties properties) {
        if (!isCacheEnabled(properties) || !StringUtils.hasText(cacheKey)) {
            return;
        }
        cache.put(cacheKey, new CachedRuleContext(copyContexts(contexts)));
        evictIfNeeded(properties);
    }

    private void evictIfNeeded(RagProperties properties) {
        int maxSize = Math.max(1, properties.getCacheMaxSize());
        if (cache.size() <= maxSize) {
            return;
        }
        cache.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().lastAccessedAt()))
                .limit(Math.max(1, cache.size() - maxSize))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(cache::remove);
    }

    private boolean isCacheEnabled(RagProperties properties) {
        return properties.isCacheEnabled()
                && properties.getCacheMaxSize() > 0
                && properties.getCacheTtlSeconds() > 0;
    }

    private Duration cacheTtl(RagProperties properties) {
        return Duration.ofSeconds(Math.max(1, properties.getCacheTtlSeconds()));
    }

    private List<ReviewRuleContext> copyContexts(List<ReviewRuleContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return List.of();
        }
        return contexts.stream()
                .filter(Objects::nonNull)
                .map(this::copyContext)
                .toList();
    }

    private ReviewRuleContext copyContext(ReviewRuleContext source) {
        ReviewRuleContext copy = new ReviewRuleContext();
        copy.setChunkId(source.getChunkId());
        copy.setDocumentId(source.getDocumentId());
        copy.setType(source.getType());
        copy.setContent(source.getContent());
        copy.setDistance(source.getDistance());
        return copy;
    }

    private static class CachedRuleContext {

        private final List<ReviewRuleContext> contexts;

        private final Instant createdAt;

        private volatile Instant lastAccessedAt;

        private CachedRuleContext(List<ReviewRuleContext> contexts) {
            Instant now = Instant.now();
            this.contexts = contexts == null ? List.of() : contexts;
            this.createdAt = now;
            this.lastAccessedAt = now;
        }

        private boolean isExpired(Duration ttl) {
            return createdAt.plus(ttl).isBefore(Instant.now());
        }

        private void markAccessed() {
            lastAccessedAt = Instant.now();
        }

        private List<ReviewRuleContext> contexts() {
            return contexts;
        }

        private Instant lastAccessedAt() {
            return lastAccessedAt;
        }
    }
}
