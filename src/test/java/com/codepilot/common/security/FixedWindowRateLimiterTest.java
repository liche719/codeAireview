package com.codepilot.common.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

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

    private Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }
}
