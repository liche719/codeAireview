package com.codepilot.module.agent.dto;

import com.codepilot.module.review.instruction.PathInstruction;

import java.util.List;

public record AiReviewRequest(
        Long taskId,
        String filePath,
        String patch,
        AiReviewContext context,
        List<DismissedFinding> dismissedFindings,
        List<PathInstruction> pathInstructions
) {

    public AiReviewRequest(
            Long taskId,
            String filePath,
            String patch,
            AiReviewContext context,
            List<DismissedFinding> dismissedFindings
    ) {
        this(taskId, filePath, patch, context, dismissedFindings, List.of());
    }

    public AiReviewRequest(Long taskId, String filePath, String patch, AiReviewContext context) {
        this(taskId, filePath, patch, context, List.of());
    }

    public AiReviewRequest(Long taskId, String filePath, String patch, List<String> allChangedFiles) {
        this(taskId, filePath, patch, AiReviewContext.fromChangedFiles(allChangedFiles));
    }

    public AiReviewRequest {
        context = context == null ? AiReviewContext.empty() : context;
        dismissedFindings = dismissedFindings == null ? List.of() : List.copyOf(dismissedFindings);
        pathInstructions = pathInstructions == null ? List.of() : List.copyOf(pathInstructions);
    }

    public List<String> allChangedFiles() {
        return context.allChangedFiles();
    }
}
