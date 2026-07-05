package com.codepilot.module.agent.prompt;

import com.codepilot.module.agent.dto.AiReviewContext;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

class AiReviewPlanPromptFormatter {

    private static final int IMPACT_PLAN_ITEM_LIMIT = 10;

    private static final int REVIEW_PLAN_RISK_AREA_LIMIT = 8;

    private static final int REVIEW_PLAN_PRIORITY_FILE_LIMIT = 8;

    private static final int REVIEW_PLAN_FILE_FOCUS_LIMIT = 4;

    private static final int REVIEW_PLAN_CROSS_FILE_FOCUS_LIMIT = 6;

    private static final int REVIEW_PLAN_WARNING_LIMIT = 6;

    boolean appendReviewPlan(
            StringBuilder builder,
            AiReviewContext.ReviewPlan reviewPlan,
            String currentFilePath
    ) {
        if (reviewPlan == null || reviewPlan.isEmpty()) {
            return false;
        }
        builder.append("\nSemantic review plan (deterministic, patch-derived, not a full repository graph):\n")
                .append("- confidence: ")
                .append(reviewPlan.confidence())
                .append(", requires repo context: ")
                .append(reviewPlan.requiresRepoContext())
                .append('\n');
        appendReviewPlanList(builder, "change types", reviewPlan.changeTypes(), IMPACT_PLAN_ITEM_LIMIT);
        appendReviewPlanRiskAreas(builder, reviewPlan.riskAreas());
        appendReviewPlanPriorityFiles(builder, reviewPlan.priorityFiles());
        appendReviewPlanCurrentFileFocus(builder, reviewPlan.fileFocuses(), currentFilePath);
        appendReviewPlanCrossFileFocuses(builder, reviewPlan.crossFileFocuses(), currentFilePath);
        appendReviewPlanList(builder, "verification hints", reviewPlan.verificationHints(), IMPACT_PLAN_ITEM_LIMIT);
        appendReviewPlanList(builder, "planner warnings", reviewPlan.plannerWarnings(), REVIEW_PLAN_WARNING_LIMIT);
        return true;
    }

    private void appendReviewPlanList(StringBuilder builder, String label, List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return;
        }
        builder.append("- ")
                .append(label)
                .append(": ")
                .append(values.stream()
                        .limit(limit)
                        .map(this::singleLine)
                        .reduce((left, right) -> left + "; " + right)
                        .orElse("N/A"));
        if (values.size() > limit) {
            builder.append("; ")
                    .append(values.size() - limit)
                    .append(" more omitted");
        }
        builder.append('\n');
    }

    private void appendReviewPlanRiskAreas(
            StringBuilder builder,
            List<AiReviewContext.ReviewPlan.RiskArea> riskAreas
    ) {
        if (riskAreas == null || riskAreas.isEmpty()) {
            return;
        }
        builder.append("- risk areas:\n");
        int limit = Math.min(riskAreas.size(), REVIEW_PLAN_RISK_AREA_LIMIT);
        for (int index = 0; index < limit; index++) {
            AiReviewContext.ReviewPlan.RiskArea riskArea = riskAreas.get(index);
            builder.append("  - ")
                    .append(singleLine(riskArea.type()))
                    .append(" [")
                    .append(singleLine(riskArea.severity()))
                    .append("]: ")
                    .append(singleLine(riskArea.reason()))
                    .append('\n');
        }
        if (riskAreas.size() > REVIEW_PLAN_RISK_AREA_LIMIT) {
            builder.append("  - ")
                    .append(riskAreas.size() - REVIEW_PLAN_RISK_AREA_LIMIT)
                    .append(" more risk areas omitted\n");
        }
    }

    private void appendReviewPlanPriorityFiles(
            StringBuilder builder,
            List<AiReviewContext.ReviewPlan.PriorityFile> priorityFiles
    ) {
        if (priorityFiles == null || priorityFiles.isEmpty()) {
            return;
        }
        builder.append("- priority files:\n");
        int limit = Math.min(priorityFiles.size(), REVIEW_PLAN_PRIORITY_FILE_LIMIT);
        for (int index = 0; index < limit; index++) {
            AiReviewContext.ReviewPlan.PriorityFile priorityFile = priorityFiles.get(index);
            builder.append("  - ")
                    .append(singleLine(priorityFile.filePath()))
                    .append(" (score=")
                    .append(priorityFile.score());
            if (!priorityFile.reasons().isEmpty()) {
                builder.append(", reasons=")
                        .append(priorityFile.reasons().stream()
                                .map(this::singleLine)
                                .reduce((left, right) -> left + "; " + right)
                                .orElse("N/A"));
            }
            builder.append(")\n");
        }
        if (priorityFiles.size() > REVIEW_PLAN_PRIORITY_FILE_LIMIT) {
            builder.append("  - ")
                    .append(priorityFiles.size() - REVIEW_PLAN_PRIORITY_FILE_LIMIT)
                    .append(" more priority files omitted\n");
        }
    }

    private void appendReviewPlanCurrentFileFocus(
            StringBuilder builder,
            List<AiReviewContext.ReviewPlan.FileFocus> fileFocuses,
            String currentFilePath
    ) {
        List<AiReviewContext.ReviewPlan.FileFocus> focuses = fileFocusesForPrompt(fileFocuses, currentFilePath);
        if (focuses.isEmpty()) {
            return;
        }
        builder.append("- current file planned focus:\n");
        int limit = Math.min(focuses.size(), REVIEW_PLAN_FILE_FOCUS_LIMIT);
        for (int index = 0; index < limit; index++) {
            AiReviewContext.ReviewPlan.FileFocus focus = focuses.get(index);
            builder.append("  - ")
                    .append(singleLine(focus.filePath()))
                    .append('\n');
            appendIndentedPlanList(builder, "focus", focus.focuses());
            appendIndentedPlanList(builder, "verify", focus.verificationHints());
            appendIndentedPlanList(builder, "related files", focus.relatedFiles());
        }
        if (focuses.size() > REVIEW_PLAN_FILE_FOCUS_LIMIT) {
            builder.append("  - ")
                    .append(focuses.size() - REVIEW_PLAN_FILE_FOCUS_LIMIT)
                    .append(" more file focuses omitted\n");
        }
    }

    private List<AiReviewContext.ReviewPlan.FileFocus> fileFocusesForPrompt(
            List<AiReviewContext.ReviewPlan.FileFocus> fileFocuses,
            String currentFilePath
    ) {
        if (fileFocuses == null || fileFocuses.isEmpty()) {
            return List.of();
        }
        List<AiReviewContext.ReviewPlan.FileFocus> safeFocuses = fileFocuses.stream()
                .filter(fileFocus -> fileFocus != null && StringUtils.hasText(fileFocus.filePath()))
                .toList();
        if (!StringUtils.hasText(currentFilePath)) {
            return safeFocuses;
        }
        String normalizedCurrentFilePath = normalizePath(currentFilePath);
        return safeFocuses.stream()
                .filter(fileFocus -> normalizePath(fileFocus.filePath()).equals(normalizedCurrentFilePath))
                .toList();
    }

    private void appendReviewPlanCrossFileFocuses(
            StringBuilder builder,
            List<AiReviewContext.ReviewPlan.CrossFileFocus> crossFileFocuses,
            String currentFilePath
    ) {
        List<AiReviewContext.ReviewPlan.CrossFileFocus> focuses =
                crossFileFocusesForPrompt(crossFileFocuses, currentFilePath);
        if (focuses.isEmpty()) {
            return;
        }
        builder.append("- cross-file planned focus:\n");
        int limit = Math.min(focuses.size(), REVIEW_PLAN_CROSS_FILE_FOCUS_LIMIT);
        for (int index = 0; index < limit; index++) {
            AiReviewContext.ReviewPlan.CrossFileFocus focus = focuses.get(index);
            builder.append("  - ")
                    .append(singleLine(focus.type()))
                    .append(" [")
                    .append(focus.files().stream()
                            .map(this::singleLine)
                            .reduce((left, right) -> left + ", " + right)
                            .orElse("N/A"))
                    .append("]: ")
                    .append(singleLine(focus.reason()));
            if (StringUtils.hasText(focus.verificationHint())) {
                builder.append(" Verify: ")
                        .append(singleLine(focus.verificationHint()));
            }
            builder.append('\n');
        }
        if (focuses.size() > REVIEW_PLAN_CROSS_FILE_FOCUS_LIMIT) {
            builder.append("  - ")
                    .append(focuses.size() - REVIEW_PLAN_CROSS_FILE_FOCUS_LIMIT)
                    .append(" more cross-file focuses omitted\n");
        }
    }

    private List<AiReviewContext.ReviewPlan.CrossFileFocus> crossFileFocusesForPrompt(
            List<AiReviewContext.ReviewPlan.CrossFileFocus> crossFileFocuses,
            String currentFilePath
    ) {
        if (crossFileFocuses == null || crossFileFocuses.isEmpty()) {
            return List.of();
        }
        List<AiReviewContext.ReviewPlan.CrossFileFocus> safeFocuses = crossFileFocuses.stream()
                .filter(focus -> focus != null && StringUtils.hasText(focus.type()) && !focus.files().isEmpty())
                .toList();
        if (!StringUtils.hasText(currentFilePath)) {
            return safeFocuses;
        }
        String normalizedCurrentFilePath = normalizePath(currentFilePath);
        return safeFocuses.stream()
                .filter(focus -> focus.files().stream()
                        .map(this::normalizePath)
                        .anyMatch(normalizedCurrentFilePath::equals))
                .toList();
    }

    private void appendIndentedPlanList(StringBuilder builder, String label, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        builder.append("    - ")
                .append(label)
                .append(": ")
                .append(values.stream()
                        .map(this::singleLine)
                        .reduce((left, right) -> left + "; " + right)
                        .orElse("N/A"))
                .append('\n');
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

    private String normalizePath(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace('\\', '/')
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
