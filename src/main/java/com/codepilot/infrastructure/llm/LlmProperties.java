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
}

