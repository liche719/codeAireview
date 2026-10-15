package com.codepilot.module.review.report;

import com.codepilot.common.enums.ReviewFileSkipCategory;
import com.codepilot.common.util.MarkdownSanitizer;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.processor.ReviewCommentBudgetAllocator;
import com.codepilot.module.review.processor.ReviewFindingRanker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ReviewReportFormatter {

    /**
     * 审查摘要的默认身份标记。
     *
     * @deprecated 标记已集中到 {@link CommentMarkers}。保留此常量只为兼容既有引用，
     * 新代码请直接使用 {@link CommentMarkers}，避免标记再次分散导致身份冲突。
     */
    @Deprecated
    public static final String DEFAULT_COMMENT_MARKER = CommentMarkers.REVIEW_SUMMARY;

    private static final int MAX_TEXT_LENGTH = 500;

    private final String commentMarker;

    private final ReviewCommentBudgetAllocator reviewCommentBudgetAllocator;

    private final ReviewFindingRanker reviewFindingRanker;

    private final ReviewProperties reviewProperties;

    /**
     * Spring 使用的主构造器。
     *
     * <p>必须显式标注 {@code @Autowired}：本类额外提供了一个三参便捷构造器供单元测试使用，
     * 存在多个候选构造器时 Spring 无法自行决定用哪个（会在启动时抛
     * "No default constructor found"）。单元测试直接构造本类，因此发现不了这个问题，
     * 只有真实 Spring 上下文的集成测试能覆盖到。</p>
     */
    @Autowired
    public ReviewReportFormatter(
            @Value("${codepilot.github.comment-marker:}") String commentMarker,
            ReviewCommentBudgetAllocator reviewCommentBudgetAllocator,
            ReviewFindingRanker reviewFindingRanker,
            ReviewProperties reviewProperties
    ) {
        this.commentMarker = StringUtils.hasText(commentMarker)
                ? commentMarker
                : DEFAULT_COMMENT_MARKER;
        this.reviewCommentBudgetAllocator = reviewCommentBudgetAllocator;
        this.reviewFindingRanker = reviewFindingRanker;
        this.reviewProperties = reviewProperties == null ? new ReviewProperties() : reviewProperties;
    }

    /** 便捷构造器：不需要覆盖度信息时（主要供单元测试）使用默认审查配置。 */
    public ReviewReportFormatter(
            String commentMarker,
            ReviewCommentBudgetAllocator reviewCommentBudgetAllocator,
            ReviewFindingRanker reviewFindingRanker
    ) {
        this(commentMarker, reviewCommentBudgetAllocator, reviewFindingRanker, new ReviewProperties());
    }

    public String getCommentMarker() {
        return commentMarker;
    }

    /** 不带文件信息的重载：覆盖度区块会缺失，仅用于无法取得 review_file 的场景与旧测试。 */
    public String formatMarkdown(ReviewTask task, List<ReviewIssue> issues) {
        return formatMarkdown(task, issues, List.of());
    }

    /**
     * 渲染 Summary Comment 正文。
     *
     * <p>关键约束：<b>只要还有变更文件没有被审查，就绝不输出"未发现问题"</b>。
     * 否则一个 200 文件的 PR 只审了 5 个，用户会把摘要读成"整个 PR 没问题"——
     * 这是比漏报更危险的错误结论。</p>
     */
    public String formatMarkdown(ReviewTask task, List<ReviewIssue> issues, List<ReviewFile> reviewFiles) {
        List<ReviewIssue> rankedIssues = reviewFindingRanker.orderForPublish(issues);
        List<ReviewIssue> visibleIssues = reviewCommentBudgetAllocator.allocateSummaryFindings(rankedIssues);
        CoverageStats coverage = CoverageStats.of(reviewFiles);

        StringBuilder markdown = new StringBuilder();
        markdown.append(commentMarker).append("\n\n");

        appendCoverageWarning(markdown, coverage);

        if (visibleIssues.isEmpty()) {
            if (coverage.hasUnreviewedFiles()) {
                markdown.append("本次没有可报告的问题。\n\n")
                        .append("> ⚠️ 但**有 ")
                        .append(coverage.skippedFiles())
                        .append(" 个变更文件未被审查**，因此这并不代表 PR 没有问题。")
                        .append("未审查的文件与原因见下方「审查覆盖」。\n\n");
            } else {
                markdown.append("未发现问题。\n\n");
            }
        } else {
            for (int i = 0; i < visibleIssues.size(); i++) {
                appendIssue(markdown, i + 1, visibleIssues.get(i));
            }

            int totalIssues = rankedIssues.size();
            if (totalIssues > visibleIssues.size()) {
                markdown.append("另外还有 ")
                        .append(totalIssues - visibleIssues.size())
                        .append(" 条问题未展示。\n");
                long suppressedCount = rankedIssues.stream()
                        .filter(issue -> issue != null && "SUPPRESS".equalsIgnoreCase(nullToEmpty(issue.getPublishDecision())))
                        .count();
                if (suppressedCount > 0) {
                    markdown.append("其中 ")
                            .append(suppressedCount)
                            .append(" 条已被抑制。\n");
                }
                markdown.append("\n");
            }
        }

        appendCoverageSection(markdown, coverage);

        return markdown.toString();
    }

    /**
     * 覆盖不足的告警放在正文最前面，因为它是用户读摘要前必须先知道的前提。
     */
    private void appendCoverageWarning(StringBuilder markdown, CoverageStats coverage) {
        if (coverage.nothingReviewed()) {
            markdown.append("> ⚠️ **未能审查任何变更文件**（共 ")
                    .append(coverage.totalFiles())
                    .append(" 个）。本次结论**不代表 PR 没有问题**，原因见下方「审查覆盖」。\n\n");
            return;
        }
        double minRatio = reviewProperties.getMinCoverageRatio();
        if (minRatio > 0 && coverage.totalFiles() > 0 && coverage.ratio() < minRatio) {
            markdown.append("> ⚠️ **审查覆盖不足**：仅审查了 ")
                    .append(coverage.reviewedFiles())
                    .append(" / ")
                    .append(coverage.totalFiles())
                    .append(" 个变更文件（")
                    .append(Math.round(coverage.ratio() * 100))
                    .append("%）。未审查部分的问题不会被发现，请谨慎依赖本次结论。\n\n");
        }
    }

    private void appendCoverageSection(StringBuilder markdown, CoverageStats coverage) {
        if (!reviewProperties.isCoverageReportEnabled() || coverage.totalFiles() == 0) {
            return;
        }
        markdown.append("### 审查覆盖\n\n")
                .append("- 本次审查 **")
                .append(coverage.reviewedFiles())
                .append(" / ")
                .append(coverage.totalFiles())
                .append("** 个变更文件");
        if (coverage.skippedFiles() > 0) {
            markdown.append("（跳过 ").append(coverage.skippedFiles()).append(" 个）");
        }
        markdown.append("\n");

        if (!coverage.hasUnreviewedFiles()) {
            markdown.append("\n全部变更文件均已审查。\n\n");
            return;
        }

        markdown.append("\n未审查文件（按原因分组）：\n\n");
        appendSkippedGroups(markdown, coverage);
    }

    /**
     * 按跳过原因分组列出文件路径；逐个列出受 {@code coverage-max-listed-files} 约束，
     * 超出的部分折叠进 {@code <details>}，避免摘要被长文件列表淹没。
     */
    private void appendSkippedGroups(StringBuilder markdown, CoverageStats coverage) {
        int budget = Math.max(0, reviewProperties.getCoverageMaxListedFiles());
        int listed = 0;

        for (Map.Entry<String, List<String>> entry : coverage.sortedSkipGroups()) {
            List<String> paths = entry.getValue();
            int remaining = budget - listed;
            markdown.append("- **")
                    .append(skipCategoryLabel(entry.getKey()))
                    .append("**（")
                    .append(paths.size())
                    .append(" 个）");

            if (remaining <= 0) {
                markdown.append("：\n");
                appendFoldedPaths(markdown, paths, 0);
                continue;
            }
            if (paths.size() <= remaining) {
                markdown.append("：\n");
                for (String path : paths) {
                    markdown.append("  - `").append(sanitizeCodeSpan(path)).append("`\n");
                }
                listed += paths.size();
                continue;
            }
            markdown.append("：\n");
            for (int i = 0; i < remaining; i++) {
                markdown.append("  - `").append(sanitizeCodeSpan(paths.get(i))).append("`\n");
            }
            appendFoldedPaths(markdown, paths.subList(remaining, paths.size()), 0);
            listed = budget;
        }
        markdown.append("\n");
    }

    private void appendFoldedPaths(StringBuilder markdown, List<String> paths, int ignoredOffset) {
        if (paths.isEmpty()) {
            return;
        }
        markdown.append("  <details><summary>展开 ")
                .append(paths.size())
                .append(" 个未列出的文件</summary>\n\n");
        for (String path : paths) {
            markdown.append("  - `").append(sanitizeCodeSpan(path)).append("`\n");
        }
        markdown.append("\n  </details>\n");
    }

    private String skipCategoryLabel(String categoryName) {
        if (!StringUtils.hasText(categoryName)) {
            return ReviewFileSkipCategory.ALREADY_SKIPPED.description();
        }
        try {
            return ReviewFileSkipCategory.valueOf(categoryName).description();
        } catch (IllegalArgumentException exception) {
            return "其他原因";
        }
    }

    private void appendIssue(StringBuilder markdown, int index, ReviewIssue issue) {
        markdown.append("#### ")
                .append(index)
                .append(". [")
                .append(displaySeverity(issue.getSeverity()))
                .append("] ")
                .append(displayIssueType(issue))
                .append("\n\n");
        markdown.append("- **文件**: `").append(sanitizeCodeSpan(issue.getFilePath())).append("`\n");
        markdown.append("- **行号**: ").append(issue.getLineNumber() == null ? "N/A" : issue.getLineNumber()).append("\n");
        markdown.append("- **描述**: ").append(sanitizeIssueText(issue.getDescription())).append("\n");
        markdown.append("- **建议**: ").append(sanitizeIssueText(issue.getSuggestion())).append("\n\n");
        if (StringUtils.hasText(issue.getSuppressionReason())) {
            markdown.append("- **Decision**: suppressed because ")
                    .append(sanitizeIssueText(issue.getSuppressionReason()))
                    .append("\n\n");
        }
        String evidenceTrace = ReviewIssueEvidenceFormatter.compactTrace(issue);
        if (StringUtils.hasText(evidenceTrace)) {
            markdown.append("- **Evidence**: ").append(sanitizeIssueText(evidenceTrace)).append("\n\n");
        }
        markdown.append("---\n\n");
    }

    private String displaySeverity(String severity) {
        return switch (normalizeSeverity(severity)) {
            case "HIGH" -> "高";
            case "MEDIUM" -> "中";
            case "LOW" -> "低";
            default -> "低";
        };
    }

    private String normalizeSeverity(String severity) {
        return StringUtils.hasText(severity) ? severity.trim().toUpperCase(Locale.ROOT) : "LOW";
    }

    private String displayIssueType(ReviewIssue issue) {
        if (issue == null) {
            return "问题";
        }
        if (StringUtils.hasText(issue.getIssueTypeZh())) {
            return MarkdownSanitizer.sanitizeInlineText(issue.getIssueTypeZh(), 80, "问题");
        }
        return "问题";
    }

    private String sanitizeIssueText(String content) {
        return MarkdownSanitizer.sanitizeInlineText(content, MAX_TEXT_LENGTH, "N/A");
    }

    private String sanitizeCodeSpan(String content) {
        if (!StringUtils.hasText(content)) {
            return "N/A";
        }
        return content.replace('`', '\'').replaceAll("\\s+", " ").trim();
    }

    private String nullToEmpty(String content) {
        return content == null ? "" : content;
    }

    /**
     * 一次审查的文件覆盖情况。
     */
    private record CoverageStats(
            int totalFiles,
            int reviewedFiles,
            int skippedFiles,
            Map<String, List<String>> skippedByCategory
    ) {

        static CoverageStats of(List<ReviewFile> reviewFiles) {
            if (reviewFiles == null || reviewFiles.isEmpty()) {
                return new CoverageStats(0, 0, 0, Map.of());
            }
            int total = 0;
            int skipped = 0;
            Map<String, List<String>> byCategory = new LinkedHashMap<>();
            for (ReviewFile reviewFile : reviewFiles) {
                if (reviewFile == null) {
                    continue;
                }
                total++;
                if (!Boolean.TRUE.equals(reviewFile.getSkipped())) {
                    continue;
                }
                skipped++;
                String category = StringUtils.hasText(reviewFile.getSkipCategory())
                        ? reviewFile.getSkipCategory()
                        : ReviewFileSkipCategory.ALREADY_SKIPPED.name();
                byCategory.computeIfAbsent(category, key -> new ArrayList<>())
                        .add(reviewFile.getFilePath());
            }
            return new CoverageStats(total, total - skipped, skipped, byCategory);
        }

        boolean hasUnreviewedFiles() {
            return skippedFiles > 0;
        }

        boolean nothingReviewed() {
            return totalFiles > 0 && reviewedFiles == 0;
        }

        double ratio() {
            return totalFiles == 0 ? 1.0 : (double) reviewedFiles / totalFiles;
        }

        /** 文件多的原因排前面，让用户先看到影响面最大的那一类。 */
        List<Map.Entry<String, List<String>>> sortedSkipGroups() {
            List<Map.Entry<String, List<String>>> groups = new ArrayList<>(skippedByCategory.entrySet());
            groups.sort(Comparator
                    .comparingInt((Map.Entry<String, List<String>> entry) -> entry.getValue().size())
                    .reversed());
            return groups;
        }
    }
}
