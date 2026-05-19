package com.codepilot.module.review.demo;

public class DangerousReviewSample {

    public String buildUnsafeQuery(String name) {
        String sql = "SELECT * FROM users WHERE name = '" + name + "'";
        return sql;
    }

    public String getHardcodedToken() {
        return "token=ghp_demo_token_1234567890";
    }

    public void logSensitiveValue(String password) {
        System.out.println("password=" + password);
    }
}
