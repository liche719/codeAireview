package com.codepilot.module.agent.dto;

import java.util.List;

public record AiReviewRequest(
        Long taskId,
        String filePath,
        String patch,
        AiReviewContext context,
        List<DismissedFinding> dismissedFindings
) {

    public AiReviewRequest(Long taskId, String filePath, String patch, AiReviewContext context) {
        this(taskId, filePath, patch, context, List.of());
    }

    public AiReviewRequest(Long taskId, String filePath, String patch, List<String> allChangedFiles) {
        this(taskId, filePath, patch, AiReviewContext.fromChangedFiles(allChangedFiles));
    }

    public AiReviewRequest {
        context = context == null ? AiReviewContext.empty() : context;
        dismissedFindings = dismissedFindings == null ? List.of() : List.copyOf(dismissedFindings);
    }

    public List<String> allChangedFiles() {
        return context.allChangedFiles();
    }
}
