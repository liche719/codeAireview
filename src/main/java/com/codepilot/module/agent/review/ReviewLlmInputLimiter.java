package com.codepilot.module.agent.review;

import com.codepilot.infrastructure.llm.LlmProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReviewLlmInputLimiter {

    private static final String TRUNCATION_MARKER = "[TRUNCATED]";

    private final LlmProperties llmProperties;

    public boolean isPatchWithinBudget(String patch) {
        int maxPatchChars = Math.max(0, llmProperties.getMaxReviewPatchChars());
        return maxPatchChars <= 0 || length(patch) <= maxPatchChars;
    }

    public ReviewLlmInput limit(String filePath, String patch, String rulesContext, String changedFilesContext) {
        LimitedText limitedRules = limitText(rulesContext, llmProperties.getMaxReviewRulesChars());
        LimitedText limitedChangedFiles = limitText(changedFilesContext, llmProperties.getMaxReviewContextChars());
        return new ReviewLlmInput(
                filePath,
                patch,
                limitedRules.text(),
                limitedChangedFiles.text(),
                limitedRules.truncated(),
                limitedChangedFiles.truncated()
        );
    }

    private LimitedText limitText(String content, int maxChars) {
        int safeMaxChars = Math.max(0, maxChars);
        if (safeMaxChars <= 0 || content == null || content.length() <= safeMaxChars) {
            return new LimitedText(content, false);
        }
        if (safeMaxChars <= TRUNCATION_MARKER.length()) {
            return new LimitedText(TRUNCATION_MARKER.substring(0, safeMaxChars), true);
        }
        String marker = "\n" + TRUNCATION_MARKER;
        int contentLimit = Math.max(0, safeMaxChars - marker.length());
        return new LimitedText(content.substring(0, contentLimit) + marker, true);
    }

    private int length(String content) {
        return content == null ? 0 : content.length();
    }

    private record LimitedText(String text, boolean truncated) {
    }
}
