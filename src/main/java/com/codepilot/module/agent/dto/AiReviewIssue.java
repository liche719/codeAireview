package com.codepilot.module.agent.dto;

import lombok.Data;

@Data
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

