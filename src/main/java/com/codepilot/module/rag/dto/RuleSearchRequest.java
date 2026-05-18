package com.codepilot.module.rag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RuleSearchRequest {

    @NotBlank
    private String query;

    private Integer topK;

    private String type;
}
