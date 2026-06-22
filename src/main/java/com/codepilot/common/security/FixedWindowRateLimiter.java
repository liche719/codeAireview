package com.codepilot.common.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class FixedWindowRateLimiter {

    private static final int CLEANUP_INTERVAL_REQUESTS = 1000;

    private final Clock clock;

    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    private final AtomicInteger requestsSinceCleanup = new AtomicInteger();

    public FixedWindowRateLimiter() {
        this(Clock.systemUTC());
    }

    public FixedWindowRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public RateLimitDecision tryConsume(String key, int maxRequests, Duration window) {
        int safeMaxRequests = Math.max(1, maxRequests);
        long windowMillis = Math.max(1000L, window == null ? 0L : window.toMillis());
        long nowMillis = clock.millis();
        long windowStartMillis = (nowMillis / windowMillis) * windowMillis;
        long resetAtMillis = windowStartMillis + windowMillis;

        WindowCounter counter = counters.compute(key, (ignored, existing) -> {
            if (existing == null || existing.windowStartMillis != windowStartMillis) {
                return new WindowCounter(windowStartMillis, 1);
            }
            return new WindowCounter(windowStartMillis, existing.count + 1);
        });

        cleanupExpiredEntries(windowStartMillis);
        boolean allowed = counter.count <= safeMaxRequests;
        int remaining = Math.max(0, safeMaxRequests - counter.count);
        long retryAfterSeconds = Math.max(1L, (resetAtMillis - nowMillis + 999L) / 1000L);
        return new RateLimitDecision(
                allowed,
                safeMaxRequests,
                remaining,
                retryAfterSeconds,
                resetAtMillis / 1000L
        );
    }

    private void cleanupExpiredEntries(long currentWindowStartMillis) {
        if (requestsSinceCleanup.incrementAndGet() < CLEANUP_INTERVAL_REQUESTS) {
            return;
        }
        requestsSinceCleanup.set(0);
        counters.entrySet().removeIf(entry -> entry.getValue().windowStartMillis < currentWindowStartMillis);
    }

    public record RateLimitDecision(
            boolean allowed,
            int limit,
            int remaining,
            long retryAfterSeconds,
            long resetEpochSeconds
    ) {
    }

    private static class WindowCounter {

        private final long windowStartMillis;

        private final int count;

        private WindowCounter(long windowStartMillis, int count) {
            this.windowStartMillis = windowStartMillis;
            this.count = count;
        }
    }
}
