package com.codepilot.module.review.service;

public record GitHubCommentResult(boolean published, boolean skipped, String errorMessage) {

    public static GitHubCommentResult publishedResult() {
        return new GitHubCommentResult(true, false, null);
    }

    public static GitHubCommentResult skippedResult() {
        return new GitHubCommentResult(false, true, null);
    }

    public static GitHubCommentResult failedResult(String errorMessage) {
        return new GitHubCommentResult(false, false, errorMessage);
    }
}
