package com.codepilot.infrastructure.llm;

public interface LlmClient {

    LlmReviewResponse review(LlmReviewRequest request);
}

