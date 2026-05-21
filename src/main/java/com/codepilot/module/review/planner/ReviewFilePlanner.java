package com.codepilot.module.review.planner;

import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewFilePlanner {

    private final ReviewProperties reviewProperties;

    public List<ReviewFile> plan(Long taskId, List<GithubChangedFile> changedFiles) {
        List<ReviewFile> reviewFiles = changedFiles.stream()
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
        int reviewedFiles = 0;
        int totalPatchChars = 0;

        for (ReviewFile reviewFile : reviewFiles) {
            String basicSkipReason = getBasicSkipReason(reviewFile);
            if (basicSkipReason != null) {
                markSkipped(reviewFile, basicSkipReason);
                continue;
            }

            int patchLength = reviewFile.getPatch().length();
            if (isPositive(reviewProperties.getMaxPatchCharsPerFile())
                    && patchLength > reviewProperties.getMaxPatchCharsPerFile()) {
                markSkipped(reviewFile, "patch exceeds per-file review limit");
                continue;
            }

            if (isPositive(reviewProperties.getMaxFilesPerTask())
                    && reviewedFiles >= reviewProperties.getMaxFilesPerTask()) {
                markSkipped(reviewFile, "review file count limit exceeded");
                continue;
            }

            if (isPositive(reviewProperties.getMaxTotalPatchChars())
                    && totalPatchChars + patchLength > reviewProperties.getMaxTotalPatchChars()) {
                markSkipped(reviewFile, "review total patch length limit exceeded");
                continue;
            }

            reviewFile.setSkipped(false);
            reviewFile.setSkipReason(null);
            reviewedFiles++;
            totalPatchChars += patchLength;
        }

        long skippedCount = reviewFiles.stream()
                .filter(reviewFile -> Boolean.TRUE.equals(reviewFile.getSkipped()))
                .count();
        log.info("Review file limits applied, totalFiles={}, reviewedFiles={}, skippedFiles={}, maxFiles={}, maxPatchCharsPerFile={}, maxTotalPatchChars={}",
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

    private boolean shouldSkipPath(String filePath) {
        String normalizedPath = filePath
                .replace('\\', '/')
                .toLowerCase(Locale.ROOT);

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
}
