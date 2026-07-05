package com.codepilot.module.review.processor;

record ReviewIssueVerificationResult(String action, String reasonCode, String explanation) {

    static ReviewIssueVerificationResult keep(String reasonCode, String explanation) {
        return new ReviewIssueVerificationResult("KEEP", reasonCode, explanation);
    }

    static ReviewIssueVerificationResult drop() {
        return new ReviewIssueVerificationResult("DROP", null, null);
    }

    boolean keep() {
        return "KEEP".equals(action);
    }
}
