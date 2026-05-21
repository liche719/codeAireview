package com.codepilot.common.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.api-auth")
public class ApiAuthProperties {

    private boolean enabled = true;

    private String apiKey = "";

    private String headerName = "X-CodePilot-Api-Key";

    private List<String> protectedPathPatterns = new ArrayList<>(List.of("/api/**"));

    private List<String> excludePathPatterns = new ArrayList<>(List.of(
            "/api/github/webhook",
            "/api/github/webhook/**"
    ));
}
