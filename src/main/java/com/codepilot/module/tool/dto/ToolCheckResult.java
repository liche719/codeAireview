package com.codepilot.module.tool.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolCheckResult {

    private String issueType;

    private String severity;

    private String title;

    private String description;

    private String suggestion;

    public static ToolCheckResult of(
            String issueType,
            String severity,
            String title,
            String description,
            String suggestion
    ) {
        return new ToolCheckResult(issueType, severity, title, description, suggestion);
    }
}
