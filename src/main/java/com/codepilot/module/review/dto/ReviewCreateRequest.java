package com.codepilot.module.review.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ReviewCreateRequest {

    @NotBlank
    private String prUrl;
}

