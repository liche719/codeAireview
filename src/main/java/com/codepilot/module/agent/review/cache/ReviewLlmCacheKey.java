package com.codepilot.module.agent.review.cache;

public record ReviewLlmCacheKey(
        String value,
        String provider,
        String modelName,
        String promptSignature
) {
}
