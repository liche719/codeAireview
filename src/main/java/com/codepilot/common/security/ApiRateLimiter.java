package com.codepilot.common.security;

import java.time.Duration;

public interface ApiRateLimiter {

    FixedWindowRateLimiter.RateLimitDecision tryConsume(String key, int maxRequests, Duration window);
}
