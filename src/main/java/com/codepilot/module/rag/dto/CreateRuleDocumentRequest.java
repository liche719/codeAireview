package com.codepilot.module.rag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateRuleDocumentRequest {

    @NotBlank
    private String title;

    private String type;

    private String source;

    @NotBlank
    private String content;
}
