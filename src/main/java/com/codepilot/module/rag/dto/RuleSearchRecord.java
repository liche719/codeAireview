package com.codepilot.module.rag.dto;

import lombok.Data;

@Data
public class RuleSearchRecord {

    private Long chunkId;

    private Long documentId;

    /** 命中块在文档里的序号（邻块扩展要靠它） */
    private Integer chunkIndex;

    private String content;

    private String type;

    private Double distance;
}
