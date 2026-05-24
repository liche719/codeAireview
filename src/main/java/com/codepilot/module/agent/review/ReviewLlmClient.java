package com.codepilot.module.agent.review;

public interface ReviewLlmClient {

    String providerName();

    boolean isAvailable();

    String review(ReviewLlmInput input);
}
