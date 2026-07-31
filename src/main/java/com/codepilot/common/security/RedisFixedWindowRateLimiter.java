package com.codepilot.common.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class RedisFixedWindowRateLimiter implements ApiRateLimiter {

    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('PTTL', KEYS[1])
            return {current, ttl}
            """, List.class);

    private final StringRedisTemplate stringRedisTemplate;

    private final FixedWindowRateLimiter localFallback;

    @Override
    public FixedWindowRateLimiter.RateLimitDecision tryConsume(
            String key,
            int maxRequests,
            Duration window
    ) {
        int safeLimit = Math.max(1, maxRequests);
        long windowMillis = Math.max(1000L, window == null ? 0L : window.toMillis());
        try {
            List<?> result = stringRedisTemplate.execute(
                    SCRIPT,
                    List.of("codepilot:api-rate-limit:" + key),
                    String.valueOf(windowMillis)
            );
            if (result == null || result.size() < 2) {
                throw new IllegalStateException("Redis rate limit script returned no result");
            }
            long count = number(result.get(0));
            long ttlMillis = Math.max(1L, number(result.get(1)));
            boolean allowed = count <= safeLimit;
            int remaining = (int) Math.max(0L, safeLimit - count);
            long retryAfterSeconds = Math.max(1L, (ttlMillis + 999L) / 1000L);
            long resetEpochSeconds = (System.currentTimeMillis() + ttlMillis) / 1000L;
            return new FixedWindowRateLimiter.RateLimitDecision(
                    allowed, safeLimit, remaining, retryAfterSeconds, resetEpochSeconds
            );
        } catch (Exception exception) {
            log.warn("Redis API rate limiter unavailable, using local fallback, errorType={}, message={}",
                    exception.getClass().getSimpleName(), exception.getMessage());
            return localFallback.tryConsume(key, safeLimit, Duration.ofMillis(windowMillis));
        }
    }

    private long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
