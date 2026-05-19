package com.codepilot.module.review.service;

public record GitHubInlineCommentResult(int successCount, int failedCount, int skippedCount) {

    public boolean hasSuccess() {
        return successCount > 0;
    }
}
