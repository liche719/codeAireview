package com.codepilot.demo;

public class ReviewTriggerMapper {

    private static final String GITHUB_TOKEN = "ghp_demo_token_should_be_reviewed";

    public String buildUnsafeSql(String name) {
        return "select * from user where name = '" + name + "'";
    }

    public String githubTokenHint() {
        return GITHUB_TOKEN;
    }
}
