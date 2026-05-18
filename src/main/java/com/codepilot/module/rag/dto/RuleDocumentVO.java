package com.codepilot.module.rag.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RuleDocumentVO {

    private Long id;

    private String title;

    private String type;

    private String source;

    private Boolean enabled;

    private LocalDateTime createdAt;
}
