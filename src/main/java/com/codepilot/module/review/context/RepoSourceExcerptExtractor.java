package com.codepilot.module.review.context;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.review.classifier.ReviewFileClassifier;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class RepoSourceExcerptExtractor {

    private static final int DEFAULT_MAX_REPO_CONTEXT_FILES = 6;

    private static final int DEFAULT_MAX_REPO_CONTEXT_FILE_CHARS = 20000;

    private static final int DEFAULT_MAX_REPO_CONTEXT_EXCERPT_CHARS = 900;

    private final RepoSourceExcerptProvider repoSourceExcerptProvider;

    private final ReviewProperties reviewProperties;

    RepoSourceExcerptExtractor() {
        this(null, new ReviewProperties());
    }

    @Autowired
    public RepoSourceExcerptExtractor(
            RepoSourceExcerptProvider repoSourceExcerptProvider,
            ReviewProperties reviewProperties
    ) {
        this.repoSourceExcerptProvider = repoSourceExcerptProvider;
        this.reviewProperties = reviewProperties == null ? new ReviewProperties() : reviewProperties;
    }

    public List<ReviewContext.RepoSourceExcerpt> repoSourceExcerpts(
            ReviewTask task,
            List<ReviewFile> reviewFiles,
            List<ReviewContext.SemanticFileContext> semanticFileContexts,
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints
    ) {
        if (!canFetch(task) || reviewFiles == null || reviewFiles.isEmpty() || repoSourceExcerptProvider == null) {
            return List.of();
        }

        Map<String, ReviewFile> changedFilesByPath = changedFilesByPath(reviewFiles);
        if (changedFilesByPath.isEmpty()) {
            return List.of();
        }

        Map<String, ReviewContext.SemanticFileContext> semanticByPath = semanticContextByPath(semanticFileContexts);
        Map<String, ReviewContext.RepoSourceExcerpt> excerpts = new LinkedHashMap<>();
        Map<String, TextExcerpt> contentCache = new LinkedHashMap<>();
        Set<String> fetchAttempts = new LinkedHashSet<>();
        int maxFetchAttempts = Math.max(maxRepoContextFiles(), 1) * 4;
        for (SourceCandidate candidate : candidates(reviewFiles, semanticByPath, repoRelationshipHints)) {
            if (excerpts.size() >= maxRepoContextFiles() || fetchAttempts.size() >= maxFetchAttempts) {
                break;
            }
            addExcerpt(task, candidate, changedFilesByPath, contentCache, fetchAttempts, excerpts);
        }
        return List.copyOf(excerpts.values());
    }

    private boolean canFetch(ReviewTask task) {
        return task != null
                && StringUtils.hasText(task.getRepoOwner())
                && StringUtils.hasText(task.getRepoName())
                && StringUtils.hasText(task.getHeadSha());
    }

    private Map<String, ReviewFile> changedFilesByPath(List<ReviewFile> reviewFiles) {
        Map<String, ReviewFile> filesByPath = new LinkedHashMap<>();
        reviewFiles.stream()
                .filter(reviewFile -> reviewFile != null && !Boolean.TRUE.equals(reviewFile.getSkipped()))
                .filter(reviewFile -> StringUtils.hasText(reviewFile.getFilePath()))
                .filter(reviewFile -> isSupportedSourcePath(reviewFile.getFilePath()))
                .forEach(reviewFile -> filesByPath.putIfAbsent(normalizePath(reviewFile.getFilePath()), reviewFile));
        return filesByPath;
    }

    private Map<String, ReviewContext.SemanticFileContext> semanticContextByPath(
            List<ReviewContext.SemanticFileContext> semanticFileContexts
    ) {
        if (semanticFileContexts == null || semanticFileContexts.isEmpty()) {
            return Map.of();
        }
        Map<String, ReviewContext.SemanticFileContext> contextsByPath = new LinkedHashMap<>();
        semanticFileContexts.stream()
                .filter(context -> context != null && StringUtils.hasText(context.filePath()))
                .forEach(context -> contextsByPath.putIfAbsent(normalizePath(context.filePath()), context));
        return contextsByPath;
    }

    private List<SourceCandidate> candidates(
            List<ReviewFile> reviewFiles,
            Map<String, ReviewContext.SemanticFileContext> semanticByPath,
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints
    ) {
        Map<String, SourceCandidate> candidates = new LinkedHashMap<>();
        collectRelationshipCandidates(candidates, repoRelationshipHints);
        collectImportCandidates(candidates, reviewFiles, semanticByPath);
        collectSourceTestCandidates(candidates, reviewFiles);
        return List.copyOf(candidates.values());
    }

    private void collectRelationshipCandidates(
            Map<String, SourceCandidate> candidates,
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints
    ) {
        if (repoRelationshipHints == null || repoRelationshipHints.isEmpty()) {
            return;
        }
        for (ReviewContext.RepoRelationshipHint hint : repoRelationshipHints) {
            if (hint == null) {
                continue;
            }
            addCandidate(candidates, hint.sourceFile(), hint.targetFile(), hint.type() + ": " + hint.reason());
            addCandidate(candidates, hint.targetFile(), hint.sourceFile(), hint.type() + ": " + hint.reason());
        }
    }

    private void collectImportCandidates(
            Map<String, SourceCandidate> candidates,
            List<ReviewFile> reviewFiles,
            Map<String, ReviewContext.SemanticFileContext> semanticByPath
    ) {
        for (ReviewFile reviewFile : reviewFiles) {
            if (reviewFile == null || !StringUtils.hasText(reviewFile.getFilePath())) {
                continue;
            }
            String sourceFile = reviewFile.getFilePath().trim();
            ReviewContext.SemanticFileContext semanticContext = semanticByPath.get(normalizePath(sourceFile));
            if (semanticContext == null || semanticContext.imports().isEmpty()) {
                continue;
            }
            for (String importValue : semanticContext.imports()) {
                List<String> importedPaths = importedSourcePaths(sourceFile, importValue);
                for (String importedPath : importedPaths) {
                    addCandidate(
                            candidates,
                            sourceFile,
                            importedPath,
                            "IMPORT_SOURCE: source imports '" + importValue + "'; include current repo declaration context."
                    );
                }
            }
        }
    }

    private void collectSourceTestCandidates(Map<String, SourceCandidate> candidates, List<ReviewFile> reviewFiles) {
        for (ReviewFile reviewFile : reviewFiles) {
            if (reviewFile == null || !StringUtils.hasText(reviewFile.getFilePath())) {
                continue;
            }
            String filePath = reviewFile.getFilePath().trim();
            sourceTestPairPaths(filePath).forEach(candidatePath -> addCandidate(
                    candidates,
                    filePath,
                    candidatePath,
                    "SOURCE_TEST_PAIR_CANDIDATE: include likely matching source/test file from PR head."
            ));
        }
    }

    private void addCandidate(
            Map<String, SourceCandidate> candidates,
            String sourceFile,
            String relatedFile,
            String reason
    ) {
        if (!StringUtils.hasText(sourceFile)
                || !StringUtils.hasText(relatedFile)
                || !StringUtils.hasText(reason)
                || normalizePath(sourceFile).equals(normalizePath(relatedFile))
                || !isSafeRelativePath(relatedFile)
                || !isSupportedSourcePath(relatedFile)) {
            return;
        }
        candidates.putIfAbsent(
                normalizePath(sourceFile) + "\u0000" + normalizePath(relatedFile),
                new SourceCandidate(sourceFile.trim(), normalizeDisplayPath(relatedFile), reason.trim())
        );
    }

    private void addExcerpt(
            ReviewTask task,
            SourceCandidate candidate,
            Map<String, ReviewFile> changedFilesByPath,
            Map<String, TextExcerpt> contentCache,
            Set<String> fetchAttempts,
            Map<String, ReviewContext.RepoSourceExcerpt> excerpts
    ) {
        if (excerpts.size() >= maxRepoContextFiles()) {
            return;
        }
        ReviewFile changedRelatedFile = changedFilesByPath.get(normalizePath(candidate.relatedFile()));
        if (changedRelatedFile != null && !StringUtils.hasText(changedRelatedFile.getPatch())) {
            return;
        }
        TextExcerpt excerpt = sourceExcerpt(task, candidate, contentCache, fetchAttempts);
        if (!StringUtils.hasText(excerpt.text())) {
            return;
        }
        excerpts.putIfAbsent(
                normalizePath(candidate.sourceFile()) + "\u0000" + normalizePath(candidate.relatedFile()),
                new ReviewContext.RepoSourceExcerpt(
                        candidate.sourceFile(),
                        candidate.relatedFile(),
                        candidate.reason(),
                        excerpt.text(),
                        excerpt.truncated()
                )
        );
    }

    private TextExcerpt sourceExcerpt(
            ReviewTask task,
            SourceCandidate candidate,
            Map<String, TextExcerpt> contentCache,
            Set<String> fetchAttempts
    ) {
        String cacheKey = normalizePath(candidate.relatedFile());
        if (contentCache.containsKey(cacheKey)) {
            return contentCache.get(cacheKey);
        }
        if (!fetchAttempts.add(cacheKey)) {
            return new TextExcerpt("", false);
        }
        try {
            String content = repoSourceExcerptProvider.getFileContent(
                    task.getRepoOwner(),
                    task.getRepoName(),
                    candidate.relatedFile(),
                    task.getHeadSha()
            );
            TextExcerpt excerpt = excerpt(content);
            contentCache.put(cacheKey, excerpt);
            return excerpt;
        } catch (Exception exception) {
            log.debug("Skip repository source excerpt because GitHub content fetch failed, owner={}, repo={}, path={}, ref={}, errorType={}",
                    task.getRepoOwner(),
                    task.getRepoName(),
                    candidate.relatedFile(),
                    task.getHeadSha(),
                    exception.getClass().getSimpleName());
            return new TextExcerpt("", false);
        }
    }

    private TextExcerpt excerpt(String content) {
        String sanitized = SensitiveDataSanitizer.redact(content);
        if (!StringUtils.hasText(sanitized)) {
            return new TextExcerpt("", false);
        }

        int maxFileChars = maxRepoContextFileChars();
        boolean truncated = maxFileChars > 0 && sanitized.length() > maxFileChars;
        String bounded = truncated ? sanitized.substring(0, maxFileChars) : sanitized;

        int maxExcerptChars = maxRepoContextExcerptChars();
        if (maxExcerptChars > 0 && bounded.length() > maxExcerptChars) {
            return new TextExcerpt(bounded.substring(0, maxExcerptChars).stripTrailing(), true);
        }
        return new TextExcerpt(bounded.stripTrailing(), truncated);
    }

    private List<String> importedSourcePaths(String sourceFile, String importValue) {
        if (!StringUtils.hasText(importValue) || importValue.endsWith(".*") || isExternalImport(importValue)) {
            return List.of();
        }
        String normalizedImport = importValue.trim().replace('\\', '/');
        if (isJvmSourcePath(sourceFile) && (normalizedImport.startsWith(".") || normalizedImport.contains("/"))) {
            return List.of();
        }
        if (normalizedImport.startsWith(".")) {
            return relativeImportPaths(sourceFile, normalizedImport);
        }
        if (!looksLikeProjectImport(normalizedImport)) {
            return List.of();
        }
        String basePath = normalizedImport.replace('.', '/');
        return packageImportSourceExtensions(sourceFile).stream()
                .map(extension -> extension.sourceRoot() + "/" + basePath + extension.suffix())
                .toList();
    }

    private List<String> relativeImportPaths(String sourceFile, String importValue) {
        String currentDirectory = directory(normalizeDisplayPath(sourceFile));
        String importPath = importValue;
        while (importPath.startsWith("../")) {
            currentDirectory = directory(currentDirectory);
            importPath = importPath.substring(3);
        }
        if (importPath.startsWith("./")) {
            importPath = importPath.substring(2);
        }
        if (!StringUtils.hasText(importPath)) {
            return List.of();
        }
        String prefix = StringUtils.hasText(currentDirectory) ? currentDirectory + "/" : "";
        String basePath = prefix + importPath.replaceFirst("\\.[^.]+$", "");
        return relativeImportSourceExtensions(sourceFile).stream()
                .map(extension -> basePath + extension.suffix())
                .toList();
    }

    private List<String> sourceTestPairPaths(String filePath) {
        String normalized = normalizeDisplayPath(filePath);
        if (!isSupportedSourcePath(normalized)) {
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

    private boolean isExternalImport(String importValue) {
        String normalized = importValue.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("java.")
                || normalized.startsWith("javax.")
                || normalized.startsWith("jakarta.")
                || normalized.startsWith("org.springframework.")
                || normalized.startsWith("org.slf4j.")
                || normalized.startsWith("org.apache.")
                || normalized.startsWith("org.junit.")
                || normalized.startsWith("org.assertj.")
                || normalized.startsWith("org.mockito.")
                || normalized.startsWith("lombok.")
                || normalized.startsWith("com.fasterxml.")
                || normalized.startsWith("com.baomidou.")
                || normalized.startsWith("dev.langchain4j.")
                || normalized.startsWith("react")
                || normalized.startsWith("@testing-library/");
    }

    private boolean looksLikeProjectImport(String importValue) {
        String normalized = importValue.trim();
        return normalized.contains(".") && !normalized.startsWith("@");
    }

    private boolean isSafeRelativePath(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        String normalized = normalizeDisplayPath(path);
        return !normalized.startsWith("/")
                && !normalized.matches("^[A-Za-z]:.*")
                && !normalized.contains("../")
                && !normalized.equals("..")
                && !normalized.contains("\u0000");
    }

    private boolean isSupportedSourcePath(String path) {
        String normalized = normalizePath(path);
        return normalized.endsWith(".java")
                || normalized.endsWith(".kt")
                || normalized.endsWith(".kts")
                || normalized.endsWith(".ts")
                || normalized.endsWith(".tsx")
                || normalized.endsWith(".js")
                || normalized.endsWith(".jsx")
                || normalized.endsWith(".mjs")
                || normalized.endsWith(".py");
    }

    private int maxRepoContextFiles() {
        int configured = reviewProperties.getMaxRepoContextFiles();
        return configured < 0 ? DEFAULT_MAX_REPO_CONTEXT_FILES : configured;
    }

    private int maxRepoContextFileChars() {
        int configured = reviewProperties.getMaxRepoContextFileChars();
        return configured < 0 ? DEFAULT_MAX_REPO_CONTEXT_FILE_CHARS : configured;
    }

    private int maxRepoContextExcerptChars() {
        int configured = reviewProperties.getMaxRepoContextExcerptChars();
        return configured < 0 ? DEFAULT_MAX_REPO_CONTEXT_EXCERPT_CHARS : configured;
    }

    private List<SourceExtension> packageImportSourceExtensions(String sourceFile) {
        String normalized = normalizePath(sourceFile);
        if (normalized.endsWith(".java")) {
            return sourceRoots(normalized, "java").stream()
                    .map(root -> new SourceExtension(root, ".java"))
                    .toList();
        }
        if (normalized.endsWith(".kt") || normalized.endsWith(".kts")) {
            return sourceRoots(normalized, "kotlin").stream()
                    .map(root -> new SourceExtension(root, ".kt"))
                    .toList();
        }
        if (normalized.endsWith(".py")) {
            return List.of(new SourceExtension(directory(normalizeDisplayPath(sourceFile)), ".py"));
        }
        return List.of();
    }

    private List<SourceExtension> relativeImportSourceExtensions(String sourceFile) {
        String normalized = normalizePath(sourceFile);
        if (normalized.endsWith(".ts")) {
            return List.of(new SourceExtension("", ".ts"), new SourceExtension("", ".tsx"));
        }
        if (normalized.endsWith(".tsx")) {
            return List.of(new SourceExtension("", ".tsx"), new SourceExtension("", ".ts"));
        }
        if (normalized.endsWith(".js") || normalized.endsWith(".mjs")) {
            return List.of(new SourceExtension("", ".js"), new SourceExtension("", ".jsx"));
        }
        if (normalized.endsWith(".jsx")) {
            return List.of(new SourceExtension("", ".jsx"), new SourceExtension("", ".js"));
        }
        if (normalized.endsWith(".py")) {
            return List.of(new SourceExtension("", ".py"));
        }
        return List.of();
    }

    private List<String> sourceRoots(String sourceFile, String languageRoot) {
        if (sourceFile.startsWith("src/test/" + languageRoot + "/")) {
            return List.of("src/main/" + languageRoot, "src/test/" + languageRoot);
        }
        if (sourceFile.startsWith("src/main/" + languageRoot + "/")) {
            return List.of("src/main/" + languageRoot);
        }
        return List.of("src/main/" + languageRoot);
    }

    private boolean isJvmSourcePath(String path) {
        String normalized = normalizePath(path);
        return normalized.endsWith(".java")
                || normalized.endsWith(".kt")
                || normalized.endsWith(".kts");
    }

    private String normalizePath(String path) {
        return normalizeDisplayPath(path).toLowerCase(Locale.ROOT);
    }

    private String normalizeDisplayPath(String path) {
        return path == null ? "" : path.replace('\\', '/').trim().replaceAll("/{2,}", "/");
    }

    private String directory(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        int index = path.lastIndexOf('/');
        return index < 0 ? "" : path.substring(0, index);
    }

    private record SourceCandidate(String sourceFile, String relatedFile, String reason) {
    }

    private record TextExcerpt(String text, boolean truncated) {
    }

    private record SourceExtension(String sourceRoot, String suffix) {
    }
}
