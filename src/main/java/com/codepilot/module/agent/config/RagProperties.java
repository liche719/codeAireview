package com.codepilot.module.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.rag")
public class RagProperties {

    private boolean enabled = true;

    private int topK = 3;

    private int maxContextChars = 2000;

    private int minContentLength = 20;

    private boolean cacheEnabled = true;

    private int cacheMaxSize = 256;

    private int cacheTtlSeconds = 300;
}
