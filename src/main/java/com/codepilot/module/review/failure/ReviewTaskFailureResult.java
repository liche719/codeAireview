package com.codepilot.module.review.failure;

public record ReviewTaskFailureResult(String errorType, String errorMessage, boolean finalAttempt) {
}
