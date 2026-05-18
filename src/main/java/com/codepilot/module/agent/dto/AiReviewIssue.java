package com.codepilot.module.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiReviewIssue {

    private String filePath;

    private Integer lineNumber;

    private String issueType;

    private String severity;

    private String title;

    private String description;

    private String suggestion;

    private String ruleReference;
}
