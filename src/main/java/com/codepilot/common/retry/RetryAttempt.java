package com.codepilot.common.retry;

public record RetryAttempt(int attempt, int maxAttempts, boolean finalAttempt) {

    public String detail(String errorType) {
        return "attempt=" + attempt + "/" + maxAttempts + ", errorType=" + errorType;
    }
}
