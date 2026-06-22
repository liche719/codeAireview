package com.codepilot.common.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {

    @Test
    void shouldRejectRequestsOverLimitWithinWindow() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(fixedClock("2026-05-24T09:00:00Z"));

        assertThat(limiter.tryConsume("api-key:demo", 2, Duration.ofMinutes(1)).allowed()).isTrue();
        assertThat(limiter.tryConsume("api-key:demo", 2, Duration.ofMinutes(1)).allowed()).isTrue();
        FixedWindowRateLimiter.RateLimitDecision denied =
                limiter.tryConsume("api-key:demo", 2, Duration.ofMinutes(1));

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.limit()).isEqualTo(2);
        assertThat(denied.remaining()).isZero();
        assertThat(denied.retryAfterSeconds()).isPositive();
    }

    @Test
    void shouldIsolateCountersByKey() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(fixedClock("2026-05-24T09:00:00Z"));

        assertThat(limiter.tryConsume("api-key:left", 1, Duration.ofMinutes(1)).allowed()).isTrue();
        assertThat(limiter.tryConsume("api-key:left", 1, Duration.ofMinutes(1)).allowed()).isFalse();
        assertThat(limiter.tryConsume("api-key:right", 1, Duration.ofMinutes(1)).allowed()).isTrue();
    }

    @Test
    void shouldEnforceLimitWhenRequestsArriveConcurrently() throws Exception {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(fixedClock("2026-05-24T09:00:00Z"));
        int requestCount = 100;
        int maxRequests = 25;
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Boolean>> results = java.util.stream.IntStream.range(0, requestCount)
                    .mapToObj(ignored -> executor.submit(() -> {
                        start.await();
                        return limiter.tryConsume("ip:127.0.0.1", maxRequests, Duration.ofMinutes(1)).allowed();
                    }))
                    .toList();

            start.countDown();

            long allowedCount = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    allowedCount++;
                }
            }

            assertThat(allowedCount).isEqualTo(maxRequests);
        } finally {
            executor.shutdownNow();
        }
    }

    private Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }
}
