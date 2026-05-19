package com.codepilot.infrastructure.embedding;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.embedding")
public class EmbeddingProperties {

    private boolean enabled = true;

    private String provider = "openai-compatible";

    private String baseUrl = "https://api.openai.com/v1";

    private String apiKey = "";

    private String model = "text-embedding-3-small";

    /**
     * Embedding dimension. 0 means auto-detect at startup.
     */
    private int dimension = 0;

    private int timeoutSeconds = 60;
}
