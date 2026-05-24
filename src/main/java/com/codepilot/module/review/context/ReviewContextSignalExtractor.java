package com.codepilot.module.review.context;

import com.codepilot.module.review.entity.ReviewFile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

@Component
public class ReviewContextSignalExtractor {

    private static final int LARGE_PR_REVIEWABLE_FILE_THRESHOLD = 10;

    private static final int LARGE_PR_PATCH_CHAR_THRESHOLD = 30000;

    public List<ReviewContext.FileSummary> fileSummaries(List<ReviewFile> reviewFiles) {
        if (reviewFiles == null || reviewFiles.isEmpty()) {
            return List.of();
        }
        return reviewFiles.stream()
                .filter(reviewFile -> StringUtils.hasText(reviewFile.getFilePath()))
                .map(this::fileSummary)
                .toList();
    }

    public List<ReviewContext.ReviewSignal> reviewSignals(List<ReviewFile> reviewFiles) {
        if (reviewFiles == null || reviewFiles.isEmpty()) {
            return List.of();
        }
        List<ReviewContext.ReviewSignal> signals = new ArrayList<>();
        long reviewableFileCount = reviewFiles.stream()
                .filter(reviewFile -> !Boolean.TRUE.equals(reviewFile.getSkipped()))
                .count();
        long skippedFileCount = reviewFiles.stream()
                .filter(reviewFile -> Boolean.TRUE.equals(reviewFile.getSkipped()))
                .count();
        int totalPatchChars = sumPatchChars(reviewFiles);

        if (reviewableFileCount >= LARGE_PR_REVIEWABLE_FILE_THRESHOLD
                || totalPatchChars >= LARGE_PR_PATCH_CHAR_THRESHOLD) {
            signals.add(new ReviewContext.ReviewSignal(
                    "LARGE_PR",
                    "MEDIUM",
                    "Large review scope detected; prioritize cross-file side effects and high-confidence findings."
            ));
        }
        if (skippedFileCount > 0) {
            signals.add(new ReviewContext.ReviewSignal(
                    "SKIPPED_FILES",
                    "MEDIUM",
                    skippedFileCount + " changed file(s) were skipped by review limits or file-type filters."
            ));
        }
        if (hasProductionCodeChange(reviewFiles) && !hasTestChange(reviewFiles)) {
            signals.add(new ReviewContext.ReviewSignal(
                    "MISSING_TEST_CHANGE",
                    "MEDIUM",
                    "Production code changed without matching test file changes."
            ));
        }
        if (hasPathMatching(reviewFiles, this::isDatabasePath)) {
            signals.add(new ReviewContext.ReviewSignal(
                    "DATABASE_CHANGE",
                    "HIGH",
                    "Database or migration files changed; check compatibility, rollback, and data safety."
            ));
        }
        if (hasPathMatching(reviewFiles, this::isSecuritySensitivePath)) {
            signals.add(new ReviewContext.ReviewSignal(
                    "SECURITY_SENSITIVE_CHANGE",
                    "HIGH",
                    "Security-sensitive files changed; check auth, secrets, permissions, and unsafe defaults."
            ));
        }
        if (hasPathMatching(reviewFiles, this::isConfigurationPath)) {
            signals.add(new ReviewContext.ReviewSignal(
                    "CONFIG_CHANGE",
                    "MEDIUM",
                    "Configuration files changed; check environment-specific defaults and deployment impact."
            ));
        }
        if (hasPathMatching(reviewFiles, this::isDependencyManifestPath)) {
            signals.add(new ReviewContext.ReviewSignal(
                    "DEPENDENCY_CHANGE",
                    "MEDIUM",
                    "Dependency or build manifest changed; check supply-chain risk, version compatibility, and build reproducibility."
            ));
        }
        if (hasPathMatching(reviewFiles, this::isPublicApiPath)) {
            signals.add(new ReviewContext.ReviewSignal(
                    "PUBLIC_API_CHANGE",
                    "HIGH",
                    "Public API surface changed; check backward compatibility, auth boundaries, clients, and API tests."
            ));
        }
        return signals;
    }

    private ReviewContext.FileSummary fileSummary(ReviewFile reviewFile) {
        return new ReviewContext.FileSummary(
                reviewFile.getFilePath().trim(),
                StringUtils.hasText(reviewFile.getChangeType()) ? reviewFile.getChangeType().trim() : "unknown",
                valueOrZero(reviewFile.getAdditions()),
                valueOrZero(reviewFile.getDeletions()),
                StringUtils.hasText(reviewFile.getPatch()) ? reviewFile.getPatch().length() : 0,
                !Boolean.TRUE.equals(reviewFile.getSkipped()),
                StringUtils.hasText(reviewFile.getSkipReason()) ? reviewFile.getSkipReason().trim() : null
        );
    }

    private boolean hasProductionCodeChange(List<ReviewFile> reviewFiles) {
        return reviewFiles.stream()
                .filter(reviewFile -> !Boolean.TRUE.equals(reviewFile.getSkipped()))
                .map(ReviewFile::getFilePath)
                .filter(StringUtils::hasText)
                .map(this::normalizePath)
                .anyMatch(path -> isSourcePath(path) && !isTestPath(path));
    }

    private boolean hasTestChange(List<ReviewFile> reviewFiles) {
        return reviewFiles.stream()
                .map(ReviewFile::getFilePath)
                .filter(StringUtils::hasText)
                .map(this::normalizePath)
                .anyMatch(this::isTestPath);
    }

    private boolean hasPathMatching(List<ReviewFile> reviewFiles, Predicate<String> matcher) {
        return reviewFiles.stream()
                .map(ReviewFile::getFilePath)
                .filter(StringUtils::hasText)
                .map(this::normalizePath)
                .anyMatch(matcher);
    }

    private boolean isSourcePath(String path) {
        return path.startsWith("src/main/")
                || path.endsWith(".java")
                || path.endsWith(".kt")
                || path.endsWith(".go")
                || path.endsWith(".ts")
                || path.endsWith(".tsx")
                || path.endsWith(".js")
                || path.endsWith(".jsx")
                || path.endsWith(".py");
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

    private boolean isDatabasePath(String path) {
        return path.contains("/db/migration/")
                || path.contains("/migrations/")
                || path.endsWith(".sql")
                || path.endsWith("mapper.xml");
    }

    private boolean isSecuritySensitivePath(String path) {
        return path.contains("security")
                || path.contains("auth")
                || path.contains("permission")
                || path.contains("token")
                || path.contains("secret")
                || path.contains("credential");
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

    private boolean isPublicApiPath(String path) {
        String normalized = normalizePath(path);
        return normalized.contains("/controller/")
                || normalized.contains("/controllers/")
                || normalized.contains("/api/")
                || normalized.contains("/dto/")
                || normalized.contains("/request/")
                || normalized.contains("/response/")
                || normalized.contains("/graphql/")
                || normalized.contains("/openapi/")
                || normalized.contains("/swagger/")
                || normalized.endsWith(".proto")
                || normalized.endsWith(".graphql")
                || normalized.endsWith(".graphqls");
    }

    private int sumPatchChars(List<ReviewFile> reviewFiles) {
        return reviewFiles.stream()
                .map(ReviewFile::getPatch)
                .filter(StringUtils::hasText)
                .mapToInt(String::length)
                .sum();
    }

    private String normalizePath(String path) {
        return path.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
    }

    private String fileName(String path) {
        String normalized = normalizePath(path);
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
