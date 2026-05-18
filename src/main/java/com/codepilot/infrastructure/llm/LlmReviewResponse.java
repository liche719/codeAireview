package com.codepilot.infrastructure.llm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LlmReviewResponse {

    private boolean success;

    private String content;

    private String modelName;

    private Long costTimeMs;

    private String errorMessage;

    public static LlmReviewResponse success(String content, String modelName, long costTimeMs) {
        return new LlmReviewResponse(true, content, modelName, costTimeMs, null);
    }

    public static LlmReviewResponse failure(String modelName, long costTimeMs, String errorMessage) {
        return new LlmReviewResponse(false, null, modelName, costTimeMs, errorMessage);
    }
}

