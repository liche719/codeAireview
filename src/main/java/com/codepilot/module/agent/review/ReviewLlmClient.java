package com.codepilot.module.agent.review;

public interface ReviewLlmClient {

    String providerName();

    boolean supports(String provider);

    boolean isAvailable();

    String review(ReviewLlmInput input);
}
