package com.codepilot.module.agent.dto;

import java.util.List;

public record AiReviewRequest(
        Long taskId,
        String filePath,
        String patch,
        List<String> allChangedFiles
) {

    public AiReviewRequest {
        allChangedFiles = allChangedFiles == null ? List.of() : List.copyOf(allChangedFiles);
    }
}
