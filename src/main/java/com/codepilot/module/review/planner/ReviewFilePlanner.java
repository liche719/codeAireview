package com.codepilot.module.review.planner;

import com.codepilot.module.git.dto.GithubChangedFile;
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
        String path = normalizePath(reviewFile == null ? null : reviewFile.getFilePath());
        String patch = normalizePath(reviewFile == null ? null : reviewFile.getPatch());
        int score = 0;

        if (isSecuritySensitivePath(path) || containsAny(patch, "password", "secret", "token", "auth", "permission")) {
            score += 1000;
        }
        if (isDatabasePath(path) || containsAny(patch, "select ", "update ", "delete ", "insert ", "alter table")) {
            score += 900;
        }
        if (isPublicApiPath(path)) {
            score += 800;
        }
        if (isConfigurationPath(path)) {
            score += 700;
        }
        if (isDependencyManifestPath(path)) {
            score += 650;
        }
        if (isProductionCodePath(path)) {
            score += 600;
        }
        if (isTestPath(path)) {
            score += 350;
        }
        if (isDocumentationPath(path)) {
            score -= 250;
        }
        return score;
    }

    private boolean shouldSkipPath(String filePath) {
        String normalizedPath = normalizePath(filePath);

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

    private boolean isSecuritySensitivePath(String path) {
        return path.contains("security")
                || path.contains("auth")
                || path.contains("permission")
                || path.contains("token")
                || path.contains("secret")
                || path.contains("credential");
    }

    private boolean isDatabasePath(String path) {
        return path.contains("/db/migration/")
                || path.contains("/migrations/")
                || path.endsWith(".sql")
                || path.endsWith("mapper.xml");
    }

    private boolean isPublicApiPath(String path) {
        return path.contains("/controller/")
                || path.contains("/controllers/")
                || path.contains("/api/")
                || path.contains("/dto/")
                || path.contains("/request/")
                || path.contains("/response/")
                || path.contains("/graphql/")
                || path.contains("/openapi/")
                || path.contains("/swagger/")
                || path.endsWith(".proto")
                || path.endsWith(".graphql")
                || path.endsWith(".graphqls");
    }

    private boolean isConfigurationPath(String path) {
        return path.endsWith(".yml")
                || path.endsWith(".yaml")
                || path.endsWith(".properties")
                || path.endsWith(".toml")
                || path.endsWith(".env")
                || path.endsWith("package.json")
                || path.endsWith("tsconfig.json")
                || path.endsWith(".eslintrc.json")
                || path.contains("config.json")
                || path.startsWith(".github/workflows/")
                || path.equals("dockerfile")
                || path.endsWith("/dockerfile")
                || path.contains("docker-compose");
    }

    private boolean isDependencyManifestPath(String path) {
        String fileName = fileName(path);
        return fileName.equals("pom.xml")
                || fileName.equals("build.gradle")
                || fileName.equals("build.gradle.kts")
                || fileName.equals("settings.gradle")
                || fileName.equals("settings.gradle.kts")
                || fileName.equals("gradle.properties")
                || fileName.equals("package.json")
                || fileName.equals("package-lock.json")
                || fileName.equals("yarn.lock")
                || fileName.equals("pnpm-lock.yaml")
                || fileName.equals("go.mod")
                || fileName.equals("go.sum")
                || fileName.equals("requirements.txt")
                || fileName.equals("pyproject.toml")
                || fileName.equals("poetry.lock");
    }

    private boolean isProductionCodePath(String path) {
        return (path.startsWith("src/main/")
                || path.endsWith(".java")
                || path.endsWith(".kt")
                || path.endsWith(".go")
                || path.endsWith(".ts")
                || path.endsWith(".tsx")
                || path.endsWith(".js")
                || path.endsWith(".jsx")
                || path.endsWith(".py"))
                && !isTestPath(path);
    }

    private boolean isTestPath(String path) {
        return path.contains("/test/")
                || path.contains("/tests/")
                || path.endsWith("test.java")
                || path.endsWith("tests.java")
                || path.endsWith(".spec.ts")
                || path.endsWith(".test.ts")
                || path.endsWith(".spec.tsx")
                || path.endsWith(".test.tsx")
                || path.endsWith(".spec.js")
                || path.endsWith(".test.js");
    }

    private boolean isDocumentationPath(String path) {
        return path.endsWith(".md")
                || path.endsWith(".adoc")
                || path.endsWith(".rst")
                || path.startsWith("docs/");
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

    private String fileName(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? path : path.substring(index + 1);
    }

    private String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
    }
}
