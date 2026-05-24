package com.codepilot.common.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.api-rate-limit")
public class ApiRateLimitProperties {

    private boolean enabled = true;

    private int maxRequestsPerWindow = 60;

    private Duration window = Duration.ofSeconds(60);

    private List<String> protectedPathPatterns = new ArrayList<>(List.of("/api/**"));

    private List<String> excludePathPatterns = new ArrayList<>();
}
