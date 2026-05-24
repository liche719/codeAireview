package com.codepilot.module.agent.review;

public record ReviewLlmInput(
        String filePath,
        String patch,
        String rulesContext,
        String changedFilesContext,
        boolean truncatedRulesContext,
        boolean truncatedChangedFilesContext
) {
}
