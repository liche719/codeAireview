package com.codepilot.module.review.context;

import com.codepilot.module.review.entity.ReviewFile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ReviewRelatedPatchExtractor {

    private static final int RELATED_PATCH_LIMIT = 80;

    private static final int MAX_EXCERPT_CHARS = 900;

    private static final int MAX_EXCERPT_LINES = 18;

    public List<ReviewContext.RelatedPatchExcerpt> relatedPatchExcerpts(
            List<ReviewFile> reviewFiles,
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints
    ) {
        if (reviewFiles == null || reviewFiles.size() < 2) {
            return List.of();
        }
        Map<String, ReviewFile> filesByPath = reviewableFilesByPath(reviewFiles);
        if (filesByPath.size() < 2) {
            return List.of();
        }
        Map<String, ReviewContext.RelatedPatchExcerpt> excerpts = new LinkedHashMap<>();
        collectRelationshipExcerpts(filesByPath, repoRelationshipHints, excerpts);
        collectFallbackExcerpts(filesByPath, excerpts);
        return List.copyOf(excerpts.values());
    }

    private Map<String, ReviewFile> reviewableFilesByPath(List<ReviewFile> reviewFiles) {
        Map<String, ReviewFile> filesByPath = new LinkedHashMap<>();
        reviewFiles.stream()
                .filter(reviewFile -> reviewFile != null && !Boolean.TRUE.equals(reviewFile.getSkipped()))
                .filter(reviewFile -> StringUtils.hasText(reviewFile.getFilePath()))
                .filter(reviewFile -> StringUtils.hasText(reviewFile.getPatch()))
                .forEach(reviewFile -> filesByPath.putIfAbsent(normalizePath(reviewFile.getFilePath()), reviewFile));
        return filesByPath;
    }

    private void collectRelationshipExcerpts(
            Map<String, ReviewFile> filesByPath,
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints,
            Map<String, ReviewContext.RelatedPatchExcerpt> excerpts
    ) {
        if (repoRelationshipHints == null || repoRelationshipHints.isEmpty()) {
            return;
        }
        for (ReviewContext.RepoRelationshipHint hint : repoRelationshipHints) {
            if (hint == null) {
                continue;
            }
            addExcerpt(
                    filesByPath,
                    excerpts,
                    hint.sourceFile(),
                    hint.targetFile(),
                    hint.type() + ": " + hint.reason()
            );
            addExcerpt(
                    filesByPath,
                    excerpts,
                    hint.targetFile(),
                    hint.sourceFile(),
                    hint.type() + ": " + hint.reason()
            );
            if (excerpts.size() >= RELATED_PATCH_LIMIT) {
                return;
            }
        }
    }

    private void collectFallbackExcerpts(
            Map<String, ReviewFile> filesByPath,
            Map<String, ReviewContext.RelatedPatchExcerpt> excerpts
    ) {
        List<ReviewFile> files = List.copyOf(filesByPath.values());
        for (int leftIndex = 0; leftIndex < files.size(); leftIndex++) {
            for (int rightIndex = leftIndex + 1; rightIndex < files.size(); rightIndex++) {
                ReviewFile left = files.get(leftIndex);
                ReviewFile right = files.get(rightIndex);
                String reason = fallbackReason(left.getFilePath(), right.getFilePath());
                if (!StringUtils.hasText(reason)) {
                    continue;
                }
                addExcerpt(filesByPath, excerpts, left.getFilePath(), right.getFilePath(), reason);
                addExcerpt(filesByPath, excerpts, right.getFilePath(), left.getFilePath(), reason);
                if (excerpts.size() >= RELATED_PATCH_LIMIT) {
                    return;
                }
            }
        }
    }

    private void addExcerpt(
            Map<String, ReviewFile> filesByPath,
            Map<String, ReviewContext.RelatedPatchExcerpt> excerpts,
            String sourceFile,
            String relatedFile,
            String reason
    ) {
        if (excerpts.size() >= RELATED_PATCH_LIMIT
                || !StringUtils.hasText(sourceFile)
                || !StringUtils.hasText(relatedFile)
                || normalizePath(sourceFile).equals(normalizePath(relatedFile))) {
            return;
        }
        ReviewFile relatedReviewFile = filesByPath.get(normalizePath(relatedFile));
        if (relatedReviewFile == null || !StringUtils.hasText(relatedReviewFile.getPatch())) {
            return;
        }
        PatchExcerpt patchExcerpt = excerpt(relatedReviewFile.getPatch());
        if (!StringUtils.hasText(patchExcerpt.text())) {
            return;
        }
        excerpts.putIfAbsent(
                key(sourceFile, relatedFile),
                new ReviewContext.RelatedPatchExcerpt(
                        sourceFile.trim(),
                        relatedReviewFile.getFilePath().trim(),
                        StringUtils.hasText(reason) ? reason.trim() : "related changed file",
                        patchExcerpt.text(),
                        patchExcerpt.truncated()
                )
        );
    }

    private PatchExcerpt excerpt(String patch) {
        StringBuilder builder = new StringBuilder();
        int lineCount = 0;
        boolean truncated = false;
        for (String rawLine : patch.lines().toList()) {
            if (!isUsefulPatchLine(rawLine)) {
                continue;
            }
            String line = trimPatchLine(rawLine);
            int projectedLength = builder.length() + line.length() + 1;
            if (lineCount >= MAX_EXCERPT_LINES || projectedLength > MAX_EXCERPT_CHARS) {
                truncated = true;
                break;
            }
            builder.append(line).append('\n');
            lineCount++;
        }
        return new PatchExcerpt(builder.toString().trim(), truncated);
    }

    private boolean isUsefulPatchLine(String line) {
        if (!StringUtils.hasText(line)
                || line.startsWith("diff --git")
                || line.startsWith("index ")
                || line.startsWith("+++")
                || line.startsWith("---")) {
            return false;
        }
        return line.startsWith("@@")
                || line.startsWith("+")
                || line.startsWith(" ");
    }

    private String trimPatchLine(String line) {
        String normalized = line.replace('\t', ' ').stripTrailing();
        if (normalized.length() <= 140) {
            return normalized;
        }
        return normalized.substring(0, 140) + " ...";
    }

    private String fallbackReason(String leftPath, String rightPath) {
        String left = normalizePath(leftPath);
        String right = normalizePath(rightPath);
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return null;
        }
        if (codeIdentity(left).equals(codeIdentity(right))) {
            return "matching source/test pair";
        }
        if (directory(left).equals(directory(right))) {
            return "same directory changed file";
        }
        return null;
    }

    private String key(String sourceFile, String relatedFile) {
        return normalizePath(sourceFile) + "\u0000" + normalizePath(relatedFile);
    }

    private String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
    }

    private String directory(String path) {
        String normalized = normalizePath(path);
        int index = normalized.lastIndexOf('/');
        return index < 0 ? "" : normalized.substring(0, index);
    }

    private String codeIdentity(String path) {
        String normalized = normalizePath(path)
                .replace("src/test/java/", "src/main/java/")
                .replace("src/test/kotlin/", "src/main/kotlin/")
                .replace("src/test/", "src/main/")
                .replace("/__tests__/", "/")
                .replace("/tests/", "/");
        return directory(normalized) + "/" + baseNameWithoutTestSuffix(normalized);
    }

    private String baseNameWithoutTestSuffix(String path) {
        String normalized = normalizePath(path);
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        String withoutExtension = fileName.replaceFirst("\\.[^.]+$", "");
        return withoutExtension
                .replaceFirst("(?i)(test|tests|spec)$", "")
                .replaceFirst("(?i)\\.(test|spec)$", "");
    }

    private record PatchExcerpt(String text, boolean truncated) {
    }
}
