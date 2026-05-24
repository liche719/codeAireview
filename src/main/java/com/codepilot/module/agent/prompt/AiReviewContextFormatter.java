package com.codepilot.module.agent.prompt;

import com.codepilot.module.agent.dto.AiReviewContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class AiReviewContextFormatter {

    private static final int CHANGED_FILE_CONTEXT_LIMIT = 100;

    private static final int FILE_SUMMARY_CONTEXT_LIMIT = 30;

    private static final int REVIEW_SIGNAL_CONTEXT_LIMIT = 20;

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
        appendChangedFiles(builder, allChangedFiles);
        appendReviewSignals(builder, safeContext.reviewSignals());
        appendFileSummaries(builder, safeContext.fileSummaries());
        appendSkippedFiles(builder, safeContext.skippedFiles());
        return builder.toString();
    }

    private void appendChangedFiles(StringBuilder builder, List<String> allChangedFiles) {
        int limit = Math.min(allChangedFiles.size(), CHANGED_FILE_CONTEXT_LIMIT);
        for (int index = 0; index < limit; index++) {
            builder.append(singleLine(allChangedFiles.get(index))).append('\n');
        }
        if (allChangedFiles.size() > CHANGED_FILE_CONTEXT_LIMIT) {
            builder.append("- ")
                    .append(allChangedFiles.size() - CHANGED_FILE_CONTEXT_LIMIT)
                    .append(" more changed files omitted\n");
        }
    }

    private void appendReviewSignals(StringBuilder builder, List<AiReviewContext.ReviewSignal> reviewSignals) {
        if (reviewSignals.isEmpty()) {
            return;
        }
        builder.append("\nReview signals:\n");
        int limit = Math.min(reviewSignals.size(), REVIEW_SIGNAL_CONTEXT_LIMIT);
        for (int index = 0; index < limit; index++) {
            AiReviewContext.ReviewSignal signal = reviewSignals.get(index);
            builder.append("- [")
                    .append(singleLine(signal.severity()))
                    .append("] ")
                    .append(singleLine(signal.type()))
                    .append(": ")
                    .append(singleLine(signal.message()))
                    .append('\n');
        }
        if (reviewSignals.size() > REVIEW_SIGNAL_CONTEXT_LIMIT) {
            builder.append("- ")
                    .append(reviewSignals.size() - REVIEW_SIGNAL_CONTEXT_LIMIT)
                    .append(" more review signals omitted\n");
        }
    }

    private void appendFileSummaries(StringBuilder builder, List<AiReviewContext.FileSummary> fileSummaries) {
        if (fileSummaries.isEmpty()) {
            return;
        }
        builder.append("\nFile summaries:\n");
        int limit = Math.min(fileSummaries.size(), FILE_SUMMARY_CONTEXT_LIMIT);
        for (int index = 0; index < limit; index++) {
            AiReviewContext.FileSummary fileSummary = fileSummaries.get(index);
            builder.append("- ")
                    .append(singleLine(fileSummary.filePath()))
                    .append(" (")
                    .append(singleLine(fileSummary.changeType()))
                    .append(", +")
                    .append(fileSummary.additions())
                    .append(" / -")
                    .append(fileSummary.deletions())
                    .append(", patchChars=")
                    .append(fileSummary.patchChars())
                    .append(", ")
                    .append(fileSummary.reviewable() ? "reviewable" : "skipped");
            if (!fileSummary.reviewable()) {
                builder.append(", reason=")
                        .append(singleLine(fileSummary.skipReason()));
            }
            builder.append(")\n");
        }
        if (fileSummaries.size() > FILE_SUMMARY_CONTEXT_LIMIT) {
            builder.append("- ")
                    .append(fileSummaries.size() - FILE_SUMMARY_CONTEXT_LIMIT)
                    .append(" more file summaries omitted\n");
        }
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
                    .append(singleLine(skippedFile.filePath()))
                    .append(": ")
                    .append(StringUtils.hasText(skippedFile.reason()) ? singleLine(skippedFile.reason()) : "skipped")
                    .append('\n');
        }
        if (skippedFiles.size() > SKIPPED_FILE_CONTEXT_LIMIT) {
            builder.append("- ")
                    .append(skippedFiles.size() - SKIPPED_FILE_CONTEXT_LIMIT)
                    .append(" more skipped files omitted\n");
        }
    }

    private String singleLine(String value) {
        if (!StringUtils.hasText(value)) {
            return "N/A";
        }
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .trim();
    }
}
