package com.codepilot.module.agent.dto;

import lombok.Data;

@Data
public class ReviewRuleContext {

    private Long chunkId;

    private Long documentId;

    private String type;

    private String content;

    private Double distance;
}
