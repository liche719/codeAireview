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

    /** workflow 安全规则（R13）。默认开启：这些都是可枚举的高危模式。 */
    private boolean ciWorkflowRiskEnabled = true;
}
