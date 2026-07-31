package com.codepilot.module.review.publisher;

public record ReviewCommentPublishResult(boolean published, boolean skipped, String errorMessage) {

    public static ReviewCommentPublishResult publishedResult() {
        return new ReviewCommentPublishResult(true, false, null);
    }

    public static ReviewCommentPublishResult skippedResult() {
        return new ReviewCommentPublishResult(false, true, null);
    }

    public static ReviewCommentPublishResult failedResult(String errorMessage) {
        return new ReviewCommentPublishResult(false, false, errorMessage);
    }
}
