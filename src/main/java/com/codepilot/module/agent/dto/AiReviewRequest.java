package com.codepilot.module.agent.dto;

import java.util.List;

public record AiReviewRequest(
        Long taskId,
        String filePath,
        String patch,
        AiReviewContext context
) {

    public AiReviewRequest(Long taskId, String filePath, String patch, List<String> allChangedFiles) {
        this(taskId, filePath, patch, AiReviewContext.fromChangedFiles(allChangedFiles));
    }

    public AiReviewRequest {
        context = context == null ? AiReviewContext.empty() : context;
    }

    public List<String> allChangedFiles() {
        return context.allChangedFiles();
    }
}
