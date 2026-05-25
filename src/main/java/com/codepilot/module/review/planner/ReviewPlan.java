package com.codepilot.module.review.planner;

import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;

public record ReviewPlan(
        List<String> changeTypes,
        List<RiskArea> riskAreas,
        List<PriorityFile> priorityFiles,
        List<FileFocus> fileFocuses,
        List<CrossFileFocus> crossFileFocuses,
        List<String> verificationHints,
        boolean requiresRepoContext,
        double confidence,
        List<String> plannerWarnings
) {

    private static final int CHANGE_TYPE_LIMIT = 8;

    private static final int RISK_AREA_LIMIT = 10;

    private static final int PRIORITY_FILE_LIMIT = 10;

    private static final int FILE_FOCUS_LIMIT = 30;

    private static final int CROSS_FILE_FOCUS_LIMIT = 8;

    private static final int VERIFICATION_HINT_LIMIT = 10;

    private static final int WARNING_LIMIT = 8;

    private static final int REASON_LIMIT = 3;

    private static final int RELATED_FILE_LIMIT = 8;

    private static final int CROSS_FILE_FILE_LIMIT = 4;

    public ReviewPlan {
        changeTypes = sanitizeTextList(changeTypes, CHANGE_TYPE_LIMIT);
        riskAreas = riskAreas == null
                ? List.of()
                : riskAreas.stream()
                .filter(riskArea -> riskArea != null
                        && hasText(riskArea.type())
                        && hasText(riskArea.severity())
                        && hasText(riskArea.reason()))
                .limit(RISK_AREA_LIMIT)
                .toList();
        priorityFiles = priorityFiles == null
                ? List.of()
                : priorityFiles.stream()
                .filter(priorityFile -> priorityFile != null && hasText(priorityFile.filePath()))
                .limit(PRIORITY_FILE_LIMIT)
                .toList();
        fileFocuses = fileFocuses == null
                ? List.of()
                : fileFocuses.stream()
                .filter(fileFocus -> fileFocus != null
                        && hasText(fileFocus.filePath())
                        && (!fileFocus.focuses().isEmpty()
                        || !fileFocus.verificationHints().isEmpty()
                        || !fileFocus.relatedFiles().isEmpty()))
                .limit(FILE_FOCUS_LIMIT)
                .toList();
        crossFileFocuses = crossFileFocuses == null
                ? List.of()
                : crossFileFocuses.stream()
                .filter(crossFileFocus -> crossFileFocus != null
                        && hasText(crossFileFocus.type())
                        && !crossFileFocus.files().isEmpty()
                        && hasText(crossFileFocus.reason()))
                .limit(CROSS_FILE_FOCUS_LIMIT)
                .toList();
        verificationHints = sanitizeTextList(verificationHints, VERIFICATION_HINT_LIMIT);
        confidence = round(clamp(confidence));
        plannerWarnings = sanitizeTextList(plannerWarnings, WARNING_LIMIT);
    }

    public static ReviewPlan empty() {
        return new ReviewPlan(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                0.0,
                List.of()
        );
    }

    public boolean isEmpty() {
        return changeTypes.isEmpty()
                && riskAreas.isEmpty()
                && priorityFiles.isEmpty()
                && fileFocuses.isEmpty()
                && crossFileFocuses.isEmpty()
                && verificationHints.isEmpty()
                && !requiresRepoContext
                && plannerWarnings.isEmpty();
    }

    public record RiskArea(String type, String severity, String reason) {
        public RiskArea {
            type = singleLine(type);
            severity = singleLine(severity);
            reason = singleLine(reason);
        }
    }

    public record PriorityFile(String filePath, int score, List<String> reasons) {
        public PriorityFile {
            filePath = singleLine(filePath);
            reasons = sanitizeTextList(reasons, REASON_LIMIT);
        }
    }

    public record FileFocus(
            String filePath,
            List<String> focuses,
            List<String> verificationHints,
            List<String> relatedFiles
    ) {
        public FileFocus {
            filePath = singleLine(filePath);
            focuses = sanitizeTextList(focuses, 6);
            verificationHints = sanitizeTextList(verificationHints, 4);
            relatedFiles = sanitizeTextList(relatedFiles, RELATED_FILE_LIMIT);
        }
    }

    public record CrossFileFocus(String type, List<String> files, String reason, String verificationHint) {
        public CrossFileFocus {
            type = singleLine(type);
            files = sanitizeTextList(files, CROSS_FILE_FILE_LIMIT);
            reason = singleLine(reason);
            verificationHint = singleLine(verificationHint);
        }
    }

    private static List<String> sanitizeTextList(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> sanitizedValues = new LinkedHashSet<>();
        values.stream()
                .filter(ReviewPlan::hasText)
                .map(ReviewPlan::singleLine)
                .forEach(sanitizedValues::add);
        return sanitizedValues.stream()
                .limit(limit)
                .toList();
    }

    private static String singleLine(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .trim();
    }

    private static boolean hasText(String value) {
        return StringUtils.hasText(value);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
