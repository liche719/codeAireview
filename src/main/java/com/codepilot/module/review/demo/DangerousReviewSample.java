package com.codepilot.module.review.demo;

public class DangerousReviewSample {

    public String buildUnsafeQuery(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (name.length() > 128) {
            throw new IllegalArgumentException("name 长度超限");
        }
        // 返回参数化 SQL，由调用方使用 PreparedStatement 等进行参数绑定
        return "SELECT id, name FROM users WHERE name = ?";
    }

    public String getHardcodedToken() {
        String token = System.getenv("GITHUB_TOKEN");
        return token == null ? "" : token;
    }

    public void logSensitiveValue(String password) {
        int len = password == null ? 0 : password.length();
        System.out.println("password.length=" + len);
    }
}
