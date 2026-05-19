package com.codepilot.module.reviewprobe;

public class ReviewBotProbe {

    public String buildUnsafeSql(long userId) {
        String sql = "SELECT * FROM review_user WHERE id = " + userId;
        return sql;
    }

    public String buildPotentialSecretHeader() {
        String token = "ghp_1234567890abcdef1234567890abcdef1234";
        return "Bearer " + token;
    }
}
