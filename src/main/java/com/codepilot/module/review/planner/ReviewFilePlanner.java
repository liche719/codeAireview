package com.codepilot.module.review.planner;

import com.codepilot.common.enums.ReviewFileSkipCategory;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.review.classifier.ReviewFileClassifier;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewFilePlanner {

    /**
     * 优先级排序后未入选时的默认原因：预算被更高优先级的文件占用了。
     */
    private static final SkipDecision PRIORITY_BUDGET = new SkipDecision(
            ReviewFileSkipCategory.BUDGET_PRIORITY,
            "review budget assigned to higher-priority files"
    );

    private final ReviewProperties reviewProperties;

    public List<ReviewFile> plan(Long taskId, List<GithubChangedFile> changedFiles) {
        if (changedFiles == null || changedFiles.isEmpty()) {
            return List.of();
        }
        List<ReviewFile> reviewFiles = changedFiles.stream()
                .filter(Objects::nonNull)
                .map(changedFile -> toReviewFile(taskId, changedFile))
                .toList();
        applyReviewLimits(reviewFiles);
        return reviewFiles;
    }

    private ReviewFile toReviewFile(Long taskId, GithubChangedFile changedFile) {
        ReviewFile reviewFile = new ReviewFile();
        reviewFile.setTaskId(taskId);
        reviewFile.setFilePath(changedFile.getFilename());
        reviewFile.setChangeType(changedFile.getStatus());
        reviewFile.setPatch(changedFile.getPatch());
        reviewFile.setAdditions(changedFile.getAdditions());
        reviewFile.setDeletions(changedFile.getDeletions());
        boolean skipped = changedFile.getPatch() == null || changedFile.getPatch().isBlank();
        reviewFile.setSkipped(skipped);
        if (skipped) {
            reviewFile.setSkipReason("patch is empty or file is binary/too large");
            reviewFile.setSkipCategory(ReviewFileSkipCategory.EMPTY_PATCH.name());
        }
        reviewFile.setCreatedAt(LocalDateTime.now());
        return reviewFile;
    }

    private void applyReviewLimits(List<ReviewFile> reviewFiles) {
        List<ReviewFile> candidates = new ArrayList<>();

        for (ReviewFile reviewFile : reviewFiles) {
            SkipDecision basicSkip = getBasicSkipReason(reviewFile);
            if (basicSkip != null) {
                markSkipped(reviewFile, basicSkip);
                continue;
            }

            if (isPositive(reviewProperties.getMaxPatchCharsPerFile())
                    && patchLength(reviewFile) > reviewProperties.getMaxPatchCharsPerFile()) {
                markSkipped(reviewFile, new SkipDecision(
                        ReviewFileSkipCategory.PATCH_TOO_LARGE,
                        "patch exceeds per-file review limit"
                ));
                continue;
            }

            candidates.add(reviewFile);
            markSkipped(reviewFile, PRIORITY_BUDGET);
        }

        Set<ReviewFile> selectedFiles = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<ReviewFile, SkipDecision> budgetSkipReasons = new IdentityHashMap<>();
        int reviewedFiles = 0;
        int totalPatchChars = 0;

        for (ReviewFile reviewFile : candidates.stream()
                .sorted(Comparator.comparingInt(this::reviewPriority).reversed())
                .toList()) {
            int patchLength = patchLength(reviewFile);
            if (isPositive(reviewProperties.getMaxFilesPerTask())
                    && reviewedFiles >= reviewProperties.getMaxFilesPerTask()) {
                budgetSkipReasons.put(reviewFile, new SkipDecision(
                        ReviewFileSkipCategory.FILE_COUNT_BUDGET,
                        "review file count limit exceeded"
                ));
                continue;
            }

            if (isPositive(reviewProperties.getMaxTotalPatchChars())
                    && totalPatchChars + patchLength > reviewProperties.getMaxTotalPatchChars()) {
                budgetSkipReasons.put(reviewFile, new SkipDecision(
                        ReviewFileSkipCategory.TOTAL_PATCH_BUDGET,
                        "review total patch length limit exceeded"
                ));
                continue;
            }

            selectedFiles.add(reviewFile);
            reviewedFiles++;
            totalPatchChars += patchLength;
        }

        for (ReviewFile reviewFile : candidates) {
            if (selectedFiles.contains(reviewFile)) {
                reviewFile.setSkipped(false);
                reviewFile.setSkipReason(null);
                reviewFile.setSkipCategory(null);
            } else {
                markSkipped(reviewFile, budgetSkipReasons.getOrDefault(reviewFile, PRIORITY_BUDGET));
            }
        }

        long skippedCount = reviewFiles.stream()
                .filter(reviewFile -> Boolean.TRUE.equals(reviewFile.getSkipped()))
                .count();
        log.info("Review file limits applied, totalFiles={}, reviewedFiles={}, skippedFiles={}, maxFiles={}, maxPatchCharsPerFile={}, maxTotalPatchChars={}, planner=priority",
                reviewFiles.size(),
                reviewedFiles,
                skippedCount,
                reviewProperties.getMaxFilesPerTask(),
                reviewProperties.getMaxPatchCharsPerFile(),
                reviewProperties.getMaxTotalPatchChars());
    }

    private SkipDecision getBasicSkipReason(ReviewFile reviewFile) {
        if (Boolean.TRUE.equals(reviewFile.getSkipped())) {
            // 保留进入规划前已经写好的更精确分类，不要一律改写成 ALREADY_SKIPPED——
            // 否则 EMPTY_PATCH 这类具体原因会在摘要里退化成"进入规划前已跳过"，用户就看不出真实原因了。
            return new SkipDecision(
                    existingCategory(reviewFile),
                    StringUtils.hasText(reviewFile.getSkipReason())
                            ? reviewFile.getSkipReason()
                            : "file is already marked skipped"
            );
        }
        if (!StringUtils.hasText(reviewFile.getPatch())) {
            return new SkipDecision(
                    ReviewFileSkipCategory.EMPTY_PATCH,
                    "patch is empty or file is binary/too large"
            );
        }
        if (!StringUtils.hasText(reviewFile.getFilePath())) {
            return new SkipDecision(ReviewFileSkipCategory.INVALID_PATH, "file path is empty");
        }
        // 生成物先判：它的处置意图（去改生成源）与普通路径排除不同，
        // 分成两个 skip 分类才能让摘要里的「审查覆盖」给出正确指引。
        if (ReviewFileClassifier.isGeneratedPath(reviewFile.getFilePath())) {
            return new SkipDecision(
                    ReviewFileSkipCategory.GENERATED,
                    "generated code skipped; change the generator source instead"
            );
        }
        if (ReviewFileClassifier.isVendoredPath(reviewFile.getFilePath())) {
            return new SkipDecision(
                    ReviewFileSkipCategory.VENDORED,
                    "vendored third-party code skipped"
            );
        }
        if (shouldSkipPath(reviewFile.getFilePath())) {
            return new SkipDecision(
                    ReviewFileSkipCategory.PATH_EXCLUDED,
                    "build output, lock file or minified asset skipped"
            );
        }
        return null;
    }

    private void markSkipped(ReviewFile reviewFile, SkipDecision decision) {
        reviewFile.setSkipped(true);
        reviewFile.setSkipReason(decision.reason());
        reviewFile.setSkipCategory(decision.category().name());
    }

    /**
     * 读取已写入的分类；缺失或无法识别时退回 {@link ReviewFileSkipCategory#ALREADY_SKIPPED}。
     */
    private ReviewFileSkipCategory existingCategory(ReviewFile reviewFile) {
        String category = reviewFile.getSkipCategory();
        if (!StringUtils.hasText(category)) {
            return ReviewFileSkipCategory.ALREADY_SKIPPED;
        }
        try {
            return ReviewFileSkipCategory.valueOf(category);
        } catch (IllegalArgumentException exception) {
            log.warn("Unknown review file skip category, falling back, filePath={}, category={}",
                    reviewFile.getFilePath(), category);
            return ReviewFileSkipCategory.ALREADY_SKIPPED;
        }
    }

    /**
     * 一次跳过判定的两个面：机器可读的分类（用于分组统计）与给人读的原因（用于解释）。
     */
    private record SkipDecision(ReviewFileSkipCategory category, String reason) {
    }

    private boolean isPositive(int value) {
        return value > 0;
    }

    private int patchLength(ReviewFile reviewFile) {
        return reviewFile == null || reviewFile.getPatch() == null ? 0 : reviewFile.getPatch().length();
    }

    private int reviewPriority(ReviewFile reviewFile) {
        String path = ReviewFileClassifier.normalizePath(reviewFile == null ? null : reviewFile.getFilePath());
        String patch = reviewFile == null || reviewFile.getPatch() == null
                ? ""
                : reviewFile.getPatch().toLowerCase(Locale.ROOT);
        int score = 0;

        if (ReviewFileClassifier.isSecuritySensitivePath(path)
                || containsAny(patch, "password", "secret", "token", "auth", "permission")) {
            score += 1000;
        }
        if (ReviewFileClassifier.isDatabasePath(path)
                || containsAny(patch, "select ", "update ", "delete ", "insert ", "alter table")) {
            score += 900;
        }
        if (ReviewFileClassifier.isPublicApiPath(path)) {
            score += 800;
        }
        // i18n / 资源包先判定，并且**与"配置文件"加成互斥**。
        //
        // 关键：`messages_zh_CN.properties` 会命中 isConfigurationPath（+700），
        // 再叠加 `src/main/` 带来的源码加成（+600），总分会**高于**普通源码——
        // 也就是说"只降优先级"的意图会被这两条既有规则完全反转，i18n 反而先被审。
        // 因此这里必须让两者互斥，而不是简单地再减一个数去抵消。
        boolean i18n = ReviewFileClassifier.isI18nPath(path);
        if (!i18n && ReviewFileClassifier.isConfigurationPath(path)) {
            score += 700;
        }
        if (ReviewFileClassifier.isDependencyManifestPath(path)) {
            score += 650;
        }
        if (ReviewFileClassifier.isProductionCodePath(path)) {
            score += 600;
        }
        if (ReviewFileClassifier.isTestPath(path)) {
            score += 350;
        }
        if (ReviewFileClassifier.isDocumentationPath(path)) {
            score -= 250;
        }
        // 翻译错误（占位符不匹配、漏翻、键名拼错）是真实缺陷，所以**不跳过**；
        // 但这类文件通常量大、单文件价值低于生产代码，因此降优先级，让预算先给别处。
        if (i18n) {
            score -= 400;
        }
        return score;
    }

    private boolean shouldSkipPath(String filePath) {
        String normalizedPath = ReviewFileClassifier.normalizePath(filePath);

        return normalizedPath.endsWith(".lock")
                || normalizedPath.endsWith("package-lock.json")
                || normalizedPath.endsWith("yarn.lock")
                // pnpm-lock.yaml 原先漏掉了：它以 .yaml 结尾，既不被 .lock 命中，
                // 又被 isDependencyManifestPath 归为依赖清单，于是会被当成普通配置送审。
                || normalizedPath.endsWith("pnpm-lock.yaml")
                || normalizedPath.endsWith("poetry.lock")
                || normalizedPath.endsWith("go.sum")
                || normalizedPath.endsWith(".min.js")
                || normalizedPath.endsWith(".min.css")
                || matchesExtraSkipPattern(normalizedPath)
                || normalizedPath.startsWith("dist/")
                || normalizedPath.contains("/dist/")
                || normalizedPath.startsWith("target/")
                || normalizedPath.contains("/target/")
                || normalizedPath.startsWith("build/")
                || normalizedPath.contains("/build/");
    }

    /**
     * 运维追加的自定义跳过规则（R11）。
     *
     * <p>只在<b>路径</b>上做 {@code contains} 匹配，且规则本身为空时直接跳过——
     * 一个空白规则如果参与匹配会命中所有文件，把整个仓库静默排除掉。</p>
     */
    private boolean matchesExtraSkipPattern(String normalizedPath) {
        if (reviewProperties == null || reviewProperties.getExtraSkipPatterns() == null) {
            return false;
        }
        for (String pattern : reviewProperties.getExtraSkipPatterns()) {
            if (!StringUtils.hasText(pattern)) {
                continue;
            }
            String needle = ReviewFileClassifier.normalizePath(pattern);
            if (StringUtils.hasText(needle) && normalizedPath.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String content, String... needles) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        for (String needle : needles) {
            if (content.contains(needle)) {
                return true;
            }
        }
        return false;
    }

}
