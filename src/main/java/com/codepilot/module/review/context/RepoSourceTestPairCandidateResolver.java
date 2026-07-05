package com.codepilot.module.review.context;

import com.codepilot.module.review.classifier.ReviewFileClassifier;
import org.springframework.util.StringUtils;

import java.util.List;

class RepoSourceTestPairCandidateResolver {

    List<RepoSourceCandidate> candidates(String filePath) {
        if (!StringUtils.hasText(filePath)) {
            return List.of();
        }
        return sourceTestPairPaths(filePath).stream()
                .map(candidatePath -> new RepoSourceCandidate(
                        filePath,
                        candidatePath,
                        "SOURCE_TEST_PAIR_CANDIDATE: include likely matching source/test file from PR head."
                ))
                .toList();
    }

    private List<String> sourceTestPairPaths(String filePath) {
        String normalized = RepoSourcePathUtils.normalizeDisplayPath(filePath);
        if (!RepoSourcePathUtils.isSupportedSourcePath(normalized)) {
            return List.of();
        }
        if (!normalized.startsWith("src/main/")
                && !normalized.startsWith("src/test/")
                && !normalized.contains("/__tests__/")
                && !normalized.contains("/tests/")) {
            return List.of();
        }
        if (ReviewFileClassifier.isTestPath(normalized)) {
            return List.of(
                    normalized
                            .replace("src/test/java/", "src/main/java/")
                            .replace("src/test/kotlin/", "src/main/kotlin/")
                            .replace("src/test/", "src/main/")
                            .replace("/__tests__/", "/")
                            .replace("/tests/", "/")
                            .replaceFirst("(?i)(test|tests|spec)(\\.[^.]+)$", "$2")
                            .replaceFirst("(?i)\\.(test|spec)(\\.[^.]+)$", "$2")
            );
        }
        String testPath = normalized
                .replace("src/main/java/", "src/test/java/")
                .replace("src/main/kotlin/", "src/test/kotlin/")
                .replace("src/main/", "src/test/");
        int dotIndex = testPath.lastIndexOf('.');
        if (dotIndex < 0) {
            return List.of();
        }
        return List.of(testPath.substring(0, dotIndex) + "Test" + testPath.substring(dotIndex));
    }
}
