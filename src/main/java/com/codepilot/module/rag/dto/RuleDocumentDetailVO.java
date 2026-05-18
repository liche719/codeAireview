package com.codepilot.module.rag.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RuleDocumentDetailVO {

    private Long id;

    private String title;

    private String type;

    private String source;

    private String content;

    private Boolean enabled;

    private LocalDateTime createdAt;
}
