package com.codepilot.module.git.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ParsePrRequest {

    @NotBlank
    private String prUrl;
}

