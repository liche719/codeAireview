package com.codepilot.module.agent.prompt;

import com.codepilot.module.agent.dto.AiReviewContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class AiReviewContextFormatter {

    private static final int SKIPPED_FILE_CONTEXT_LIMIT = 20;

    public String format(AiReviewContext context) {
        AiReviewContext safeContext = context == null ? AiReviewContext.empty() : context;
        List<String> allChangedFiles = safeContext.allChangedFiles();
        if (allChangedFiles.isEmpty()) {
            return "No changed file list was provided.";
        }

        StringBuilder builder = new StringBuilder()
                .append("Changed files (")
                .append(safeContext.totalFileCount())
                .append(" total, ")
                .append(safeContext.reviewableFileCount())
                .append(" reviewable, ")
                .append(safeContext.skippedFileCount())
                .append(" skipped, +")
                .append(safeContext.totalAdditions())
                .append(" / -")
                .append(safeContext.totalDeletions())
                .append(", patchChars=")
                .append(safeContext.totalPatchChars())
                .append("):\n");
        builder.append(String.join("\n", allChangedFiles));
        appendSkippedFiles(builder, safeContext.skippedFiles());
        return builder.toString();
    }

    private void appendSkippedFiles(StringBuilder builder, List<AiReviewContext.SkippedFile> skippedFiles) {
        if (skippedFiles.isEmpty()) {
            return;
        }
        builder.append("\n\nSkipped files:\n");
        int limit = Math.min(skippedFiles.size(), SKIPPED_FILE_CONTEXT_LIMIT);
        for (int index = 0; index < limit; index++) {
            AiReviewContext.SkippedFile skippedFile = skippedFiles.get(index);
            builder.append("- ")
                    .append(skippedFile.filePath())
                    .append(": ")
                    .append(StringUtils.hasText(skippedFile.reason()) ? skippedFile.reason() : "skipped")
                    .append('\n');
        }
        if (skippedFiles.size() > SKIPPED_FILE_CONTEXT_LIMIT) {
            builder.append("- ")
                    .append(skippedFiles.size() - SKIPPED_FILE_CONTEXT_LIMIT)
                    .append(" more skipped files omitted\n");
        }
    }
}
