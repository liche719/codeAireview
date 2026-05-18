package com.codepilot.module.tool.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.tools")
public class ToolProperties {

    private boolean enabled = true;

    private boolean sqlRiskEnabled = true;

    private boolean secretScanEnabled = true;

    private boolean testSuggestionEnabled = true;
}
