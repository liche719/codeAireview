package com.codepilot.module.agent.prompt;

import com.codepilot.module.agent.dto.AiReviewContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AiReviewContextFormatter {

    private static final int CHANGED_FILE_CONTEXT_LIMIT = 100;

    private static final int FILE_SUMMARY_CONTEXT_LIMIT = 30;

    private static final int REVIEW_SIGNAL_CONTEXT_LIMIT = 20;

    private static final int SKIPPED_FILE_CONTEXT_LIMIT = 20;

    private static final int RELATED_FILE_CONTEXT_LIMIT = 10;

    private static final int SEMANTIC_CONTEXT_LIMIT = 12;

    private static final int REPO_RELATIONSHIP_CONTEXT_LIMIT = 20;

    private static final int IMPACT_PLAN_ITEM_LIMIT = 10;

    private static final int RELATED_PATCH_EXCERPT_LIMIT = 6;

    public String format(AiReviewContext context) {
        return formatForFile(context, null);
    }

    public String formatForFile(AiReviewContext context, String currentFilePath) {
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
        appendCurrentFileFocus(builder, safeContext, currentFilePath);
        appendRelatedPatchExcerpts(builder, safeContext.relatedPatchExcerpts(), currentFilePath);
        appendReviewImpactPlan(builder, safeContext.reviewImpactPlan());
        appendSemanticContexts(builder, safeContext.semanticFileContexts(), currentFilePath);
        appendRepoRelationshipHints(builder, safeContext.repoRelationshipHints(), currentFilePath);
        appendReviewSignals(builder, safeContext.reviewSignals());
        appendFileSummaries(builder, safeContext.fileSummaries());
        appendSkippedFiles(builder, safeContext.skippedFiles());
        return builder.toString();
    }

    private void appendRelatedPatchExcerpts(
            StringBuilder builder,
            List<AiReviewContext.RelatedPatchExcerpt> relatedPatchExcerpts,
            String currentFilePath
    ) {
        List<AiReviewContext.RelatedPatchExcerpt> excerpts =
                relatedPatchExcerptsForPrompt(relatedPatchExcerpts, currentFilePath);
        if (excerpts.isEmpty()) {
            return;
        }
        builder.append("\nRelated changed-file patch excerpts (patch-derived, truncated):\n");
        int limit = Math.min(excerpts.size(), RELATED_PATCH_EXCERPT_LIMIT);
        for (int index = 0; index < limit; index++) {
            AiReviewContext.RelatedPatchExcerpt excerpt = excerpts.get(index);
            builder.append("- ")
                    .append(singleLine(excerpt.relatedFile()))
                    .append(" (")
                    .append(singleLine(excerpt.reason()))
                    .append(excerpt.truncated() ? ", truncated" : "")
                    .append("):\n");
            for (String line : excerpt.excerpt().lines().toList()) {
                builder.append("  ")
                        .append(singleLine(line))
                        .append('\n');
            }
        }
        if (excerpts.size() > RELATED_PATCH_EXCERPT_LIMIT) {
            builder.append("- ")
                    .append(excerpts.size() - RELATED_PATCH_EXCERPT_LIMIT)
                    .append(" more related patch excerpts omitted\n");
        }
    }

    private List<AiReviewContext.RelatedPatchExcerpt> relatedPatchExcerptsForPrompt(
            List<AiReviewContext.RelatedPatchExcerpt> relatedPatchExcerpts,
            String currentFilePath
    ) {
        if (relatedPatchExcerpts == null || relatedPatchExcerpts.isEmpty() || !StringUtils.hasText(currentFilePath)) {
            return List.of();
        }
        String normalizedCurrentFilePath = normalizePath(currentFilePath);
        return relatedPatchExcerpts.stream()
                .filter(excerpt -> excerpt != null
                        && StringUtils.hasText(excerpt.sourceFile())
                        && StringUtils.hasText(excerpt.relatedFile())
                        && StringUtils.hasText(excerpt.reason())
                        && StringUtils.hasText(excerpt.excerpt()))
                .filter(excerpt -> normalizePath(excerpt.sourceFile()).equals(normalizedCurrentFilePath))
                .toList();
    }

    private void appendReviewImpactPlan(
            StringBuilder builder,
            AiReviewContext.ReviewImpactPlan reviewImpactPlan
    ) {
        if (reviewImpactPlan == null || reviewImpactPlan.isEmpty()) {
            return;
        }
        builder.append("\nReview impact plan (patch-derived, not a full repository graph):\n");
        appendImpactPlanList(builder, "change types", reviewImpactPlan.changeTypes());
        appendImpactPlanList(builder, "impact areas", reviewImpactPlan.impactAreas());
        appendImpactPlanList(builder, "priority focus", reviewImpactPlan.priorityFocuses());
        appendImpactPlanList(builder, "verification hints", reviewImpactPlan.verificationHints());
    }

    private void appendImpactPlanList(StringBuilder builder, String label, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        builder.append("- ")
                .append(label)
                .append(": ")
                .append(values.stream()
                        .limit(IMPACT_PLAN_ITEM_LIMIT)
                        .map(this::singleLine)
                        .reduce((left, right) -> left + "; " + right)
                        .orElse("N/A"));
        if (values.size() > IMPACT_PLAN_ITEM_LIMIT) {
            builder.append("; ")
                    .append(values.size() - IMPACT_PLAN_ITEM_LIMIT)
                    .append(" more omitted");
        }
        builder.append('\n');
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

    private void appendCurrentFileFocus(
            StringBuilder builder,
            AiReviewContext context,
            String currentFilePath
    ) {
        if (!StringUtils.hasText(currentFilePath)) {
            return;
        }
        builder.append("\nCurrent file focus:\n")
                .append("- Current file: ")
                .append(singleLine(currentFilePath))
                .append('\n');

        Map<String, String> relatedFiles = relatedChangedFiles(context, currentFilePath);
        if (relatedFiles.isEmpty()) {
            builder.append("- Related changed files: none detected\n");
            return;
        }

        builder.append("- Related changed files:\n");
        int index = 0;
        for (Map.Entry<String, String> relatedFile : relatedFiles.entrySet()) {
            if (index >= RELATED_FILE_CONTEXT_LIMIT) {
                builder.append("  - ")
                        .append(relatedFiles.size() - RELATED_FILE_CONTEXT_LIMIT)
                        .append(" more related changed files omitted\n");
                break;
            }
            builder.append("  - ")
                    .append(singleLine(relatedFile.getKey()))
                    .append(" (")
                    .append(relatedFile.getValue())
                    .append(")\n");
            index++;
        }
    }

    private Map<String, String> relatedChangedFiles(AiReviewContext context, String currentFilePath) {
        String normalizedCurrentFilePath = normalizePath(currentFilePath);
        if (!StringUtils.hasText(normalizedCurrentFilePath)) {
            return Map.of();
        }
        Map<String, String> relatedFiles = new LinkedHashMap<>();
        changedFileCandidates(context).forEach(candidate -> {
            String normalizedCandidate = normalizePath(candidate);
            if (!StringUtils.hasText(normalizedCandidate) || normalizedCandidate.equals(normalizedCurrentFilePath)) {
                return;
            }
            String reason = relationReason(normalizedCurrentFilePath, normalizedCandidate);
            if (reason != null) {
                relatedFiles.putIfAbsent(candidate, reason);
            }
        });
        return relatedFiles;
    }

    private List<String> changedFileCandidates(AiReviewContext context) {
        if (!context.fileSummaries().isEmpty()) {
            return context.fileSummaries().stream()
                    .map(AiReviewContext.FileSummary::filePath)
                    .filter(StringUtils::hasText)
                    .toList();
        }
        return context.allChangedFiles();
    }

    private String relationReason(String currentFilePath, String candidateFilePath) {
        if (codeIdentity(currentFilePath).equals(codeIdentity(candidateFilePath))) {
            return "matching source/test pair";
        }
        if (sameBaseName(currentFilePath, candidateFilePath)) {
            return "same base name";
        }
        if (directory(currentFilePath).equals(directory(candidateFilePath))) {
            return "same directory";
        }
        return null;
    }

    private void appendSemanticContexts(
            StringBuilder builder,
            List<AiReviewContext.SemanticFileContext> semanticFileContexts,
            String currentFilePath
    ) {
        List<AiReviewContext.SemanticFileContext> contexts = semanticContextsForPrompt(semanticFileContexts, currentFilePath);
        if (contexts.isEmpty()) {
            return;
        }
        builder.append("\nSemantic diff context (patch-derived, not a full repository graph):\n");
        int limit = Math.min(contexts.size(), SEMANTIC_CONTEXT_LIMIT);
        for (int index = 0; index < limit; index++) {
            AiReviewContext.SemanticFileContext context = contexts.get(index);
            builder.append("- ")
                    .append(singleLine(context.filePath()))
                    .append(" (language=")
                    .append(singleLine(context.language()));
            if (StringUtils.hasText(context.packageName())) {
                builder.append(", package=")
                        .append(singleLine(context.packageName()));
            }
            builder.append(")\n");
            appendSemanticList(builder, "symbols", context.declaredSymbols());
            appendSemanticList(builder, "methods", context.changedMethods());
            appendSemanticList(builder, "annotations", context.annotations());
            appendSemanticList(builder, "imports", context.imports());
            appendSemanticList(builder, "routes", context.apiRoutes());
        }
        if (contexts.size() > SEMANTIC_CONTEXT_LIMIT) {
            builder.append("- ")
                    .append(contexts.size() - SEMANTIC_CONTEXT_LIMIT)
                    .append(" more semantic contexts omitted\n");
        }
    }

    private List<AiReviewContext.SemanticFileContext> semanticContextsForPrompt(
            List<AiReviewContext.SemanticFileContext> semanticFileContexts,
            String currentFilePath
    ) {
        if (semanticFileContexts == null || semanticFileContexts.isEmpty()) {
            return List.of();
        }
        List<AiReviewContext.SemanticFileContext> contexts = semanticFileContexts.stream()
                .filter(context -> context != null && StringUtils.hasText(context.filePath()))
                .filter(this::hasSemanticContent)
                .toList();
        if (!StringUtils.hasText(currentFilePath)) {
            return contexts;
        }
        String normalizedCurrentFilePath = normalizePath(currentFilePath);
        return contexts.stream()
                .sorted((left, right) -> Boolean.compare(
                        !normalizePath(left.filePath()).equals(normalizedCurrentFilePath),
                        !normalizePath(right.filePath()).equals(normalizedCurrentFilePath)
                ))
                .toList();
    }

    private boolean hasSemanticContent(AiReviewContext.SemanticFileContext context) {
        return StringUtils.hasText(context.packageName())
                || !context.declaredSymbols().isEmpty()
                || !context.changedMethods().isEmpty()
                || !context.annotations().isEmpty()
                || !context.imports().isEmpty()
                || !context.apiRoutes().isEmpty();
    }

    private void appendSemanticList(StringBuilder builder, String label, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        builder.append("  - ")
                .append(label)
                .append(": ")
                .append(values.stream()
                        .map(this::singleLine)
                        .reduce((left, right) -> left + ", " + right)
                        .orElse("N/A"))
                .append('\n');
    }

    private void appendRepoRelationshipHints(
            StringBuilder builder,
            List<AiReviewContext.RepoRelationshipHint> repoRelationshipHints,
            String currentFilePath
    ) {
        List<AiReviewContext.RepoRelationshipHint> hints =
                relationshipHintsForPrompt(repoRelationshipHints, currentFilePath);
        if (hints.isEmpty()) {
            return;
        }
        builder.append("\nRepo relationship hints (patch-derived, not a full repository graph):\n");
        int limit = Math.min(hints.size(), REPO_RELATIONSHIP_CONTEXT_LIMIT);
        for (int index = 0; index < limit; index++) {
            AiReviewContext.RepoRelationshipHint hint = hints.get(index);
            builder.append("- ")
                    .append(singleLine(hint.sourceFile()))
                    .append(" -> ")
                    .append(singleLine(hint.targetFile()))
                    .append(" [")
                    .append(singleLine(hint.type()))
                    .append("]: ")
                    .append(singleLine(hint.reason()))
                    .append('\n');
        }
        if (hints.size() > REPO_RELATIONSHIP_CONTEXT_LIMIT) {
            builder.append("- ")
                    .append(hints.size() - REPO_RELATIONSHIP_CONTEXT_LIMIT)
                    .append(" more repo relationship hints omitted\n");
        }
    }

    private List<AiReviewContext.RepoRelationshipHint> relationshipHintsForPrompt(
            List<AiReviewContext.RepoRelationshipHint> repoRelationshipHints,
            String currentFilePath
    ) {
        if (repoRelationshipHints == null || repoRelationshipHints.isEmpty()) {
            return List.of();
        }
        List<AiReviewContext.RepoRelationshipHint> hints = repoRelationshipHints.stream()
                .filter(hint -> hint != null
                        && StringUtils.hasText(hint.sourceFile())
                        && StringUtils.hasText(hint.targetFile())
                        && StringUtils.hasText(hint.type())
                        && StringUtils.hasText(hint.reason()))
                .toList();
        if (!StringUtils.hasText(currentFilePath)) {
            return hints;
        }
        String normalizedCurrentFilePath = normalizePath(currentFilePath);
        return hints.stream()
                .sorted((left, right) -> Boolean.compare(
                        !relationshipIncludesFile(left, normalizedCurrentFilePath),
                        !relationshipIncludesFile(right, normalizedCurrentFilePath)
                ))
                .toList();
    }

    private boolean relationshipIncludesFile(
            AiReviewContext.RepoRelationshipHint hint,
            String normalizedFilePath
    ) {
        return normalizePath(hint.sourceFile()).equals(normalizedFilePath)
                || normalizePath(hint.targetFile()).equals(normalizedFilePath);
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

    private String normalizePath(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace('\\', '/')
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private String directory(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? "" : path.substring(0, index);
    }

    private boolean sameBaseName(String left, String right) {
        return baseNameWithoutTestSuffix(left).equals(baseNameWithoutTestSuffix(right));
    }

    private String baseNameWithoutTestSuffix(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        String withoutExtension = fileName.replaceFirst("\\.[^.]+$", "");
        return withoutExtension
                .replaceFirst("(?i)(test|tests|spec)$", "")
                .replaceFirst("(?i)\\.(test|spec)$", "");
    }

    private String codeIdentity(String path) {
        String normalized = path
                .replace("src/test/java/", "src/main/java/")
                .replace("src/test/kotlin/", "src/main/kotlin/")
                .replace("src/test/", "src/main/")
                .replace("/__tests__/", "/")
                .replace("/tests/", "/");
        String directory = directory(normalized);
        return directory + "/" + baseNameWithoutTestSuffix(normalized);
    }

}
