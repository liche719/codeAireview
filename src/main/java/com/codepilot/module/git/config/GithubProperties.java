package com.codepilot.module.git.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.github")
public class GithubProperties {

    private AuthMode authMode = AuthMode.AUTO;

    private String apiBaseUrl = "https://api.github.com";

    private String token = "";

    private String appId = "";

    private String appPrivateKey = "";

    private String appPrivateKeyBase64 = "";

    private Long appInstallationId;

    private long appTokenCacheSkewSeconds = 60L;

    private int rateLimitMaxAttempts = 3;

    private long rateLimitInitialDelayMillis = 1000L;

    private double rateLimitBackoffMultiplier = 2.0D;

    private long rateLimitMaxDelayMillis = 10000L;

    public enum AuthMode {
        AUTO,
        PAT,
        APP
    }
}
