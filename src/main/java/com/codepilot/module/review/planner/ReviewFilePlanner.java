package com.codepilot.module.review.planner;

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
        reviewFile.setSkipReason(skipped ? "patch is empty or file is binary/too large" : null);
        reviewFile.setCreatedAt(LocalDateTime.now());
        return reviewFile;
    }

    private void applyReviewLimits(List<ReviewFile> reviewFiles) {
        List<ReviewFile> candidates = new ArrayList<>();

        for (ReviewFile reviewFile : reviewFiles) {
            String basicSkipReason = getBasicSkipReason(reviewFile);
            if (basicSkipReason != null) {
                markSkipped(reviewFile, basicSkipReason);
                continue;
            }

            if (isPositive(reviewProperties.getMaxPatchCharsPerFile())
                    && patchLength(reviewFile) > reviewProperties.getMaxPatchCharsPerFile()) {
                markSkipped(reviewFile, "patch exceeds per-file review limit");
                continue;
            }

            candidates.add(reviewFile);
            markSkipped(reviewFile, "review budget assigned to higher-priority files");
        }

        Set<ReviewFile> selectedFiles = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<ReviewFile, String> budgetSkipReasons = new IdentityHashMap<>();
        int reviewedFiles = 0;
        int totalPatchChars = 0;

        for (ReviewFile reviewFile : candidates.stream()
                .sorted(Comparator.comparingInt(this::reviewPriority).reversed())
                .toList()) {
            int patchLength = patchLength(reviewFile);
            if (isPositive(reviewProperties.getMaxFilesPerTask())
                    && reviewedFiles >= reviewProperties.getMaxFilesPerTask()) {
                budgetSkipReasons.put(reviewFile, "review file count limit exceeded");
                continue;
            }

            if (isPositive(reviewProperties.getMaxTotalPatchChars())
                    && totalPatchChars + patchLength > reviewProperties.getMaxTotalPatchChars()) {
                budgetSkipReasons.put(reviewFile, "review total patch length limit exceeded");
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
            } else {
                markSkipped(reviewFile, budgetSkipReasons.getOrDefault(
                        reviewFile,
                        "review budget assigned to higher-priority files"
                ));
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

    private String getBasicSkipReason(ReviewFile reviewFile) {
        if (Boolean.TRUE.equals(reviewFile.getSkipped())) {
            return StringUtils.hasText(reviewFile.getSkipReason()) ? reviewFile.getSkipReason() : "file is already marked skipped";
        }
        if (!StringUtils.hasText(reviewFile.getPatch())) {
            return "patch is empty or file is binary/too large";
        }
        if (!StringUtils.hasText(reviewFile.getFilePath())) {
            return "file path is empty";
        }
        if (shouldSkipPath(reviewFile.getFilePath())) {
            return "file type or generated path skipped";
        }
        return null;
    }

    private void markSkipped(ReviewFile reviewFile, String reason) {
        reviewFile.setSkipped(true);
        reviewFile.setSkipReason(reason);
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
        if (ReviewFileClassifier.isConfigurationPath(path)) {
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
        return score;
    }

    private boolean shouldSkipPath(String filePath) {
        String normalizedPath = ReviewFileClassifier.normalizePath(filePath);

        return normalizedPath.endsWith(".lock")
                || normalizedPath.endsWith("package-lock.json")
                || normalizedPath.endsWith("yarn.lock")
                || normalizedPath.endsWith(".min.js")
                || normalizedPath.startsWith("dist/")
                || normalizedPath.contains("/dist/")
                || normalizedPath.startsWith("target/")
                || normalizedPath.contains("/target/")
                || normalizedPath.startsWith("build/")
                || normalizedPath.contains("/build/");
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
