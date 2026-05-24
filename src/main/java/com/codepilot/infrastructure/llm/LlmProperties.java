package com.codepilot.infrastructure.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.llm")
public class LlmProperties {

    private boolean enabled = true;

    private String provider = "openai-compatible";

    private String baseUrl = "https://api.openai.com/v1";

    private String apiKey = "";

    private String model = "gpt-4o-mini";

    private double temperature = 0.2D;

    private int timeoutSeconds = 60;

    private int maxReviewPatchChars = 12000;

    private int maxReviewRulesChars = 4000;

    private int maxReviewContextChars = 8000;

    private boolean reviewStructuredOutputEnabled = true;

    private boolean reviewCacheEnabled = true;

    private int reviewCacheTtlDays = 7;

    private boolean reviewCacheCleanupEnabled = true;

    private boolean callLogCleanupEnabled = true;

    private int callLogRetentionDays = 30;
}
