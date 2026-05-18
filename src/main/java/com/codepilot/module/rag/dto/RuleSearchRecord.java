package com.codepilot.module.rag.dto;

import lombok.Data;

@Data
public class RuleSearchRecord {

    private Long chunkId;

    private Long documentId;

    private String content;

    private String type;

    private Double distance;
}
