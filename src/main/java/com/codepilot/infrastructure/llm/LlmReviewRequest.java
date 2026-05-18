package com.codepilot.infrastructure.llm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LlmReviewRequest {

    private Long taskId;

    private String filePath;

    private String prompt;

    private Integer patchLength;
}

