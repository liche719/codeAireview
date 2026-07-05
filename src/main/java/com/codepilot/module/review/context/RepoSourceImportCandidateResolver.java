package com.codepilot.module.review.context;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

class RepoSourceImportCandidateResolver {

    List<RepoSourceCandidate> candidates(String sourceFile, List<String> imports) {
        if (!StringUtils.hasText(sourceFile) || imports == null || imports.isEmpty()) {
            return List.of();
        }
        return imports.stream()
                .filter(StringUtils::hasText)
                .flatMap(importValue -> importedSourcePaths(sourceFile, importValue).stream()
                        .map(importedPath -> new RepoSourceCandidate(
                                sourceFile,
                                importedPath,
                                "IMPORT_SOURCE: source imports '" + importValue
                                        + "'; include current repo declaration context."
                        )))
                .toList();
    }

    private List<String> importedSourcePaths(String sourceFile, String importValue) {
        if (!StringUtils.hasText(importValue) || importValue.endsWith(".*") || isExternalImport(importValue)) {
            return List.of();
        }
        String normalizedImport = importValue.trim().replace('\\', '/');
        if (RepoSourcePathUtils.isJvmSourcePath(sourceFile)
                && (normalizedImport.startsWith(".") || normalizedImport.contains("/"))) {
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
        String currentDirectory = RepoSourcePathUtils.directory(RepoSourcePathUtils.normalizeDisplayPath(sourceFile));
        String importPath = importValue;
        while (importPath.startsWith("../")) {
            currentDirectory = RepoSourcePathUtils.directory(currentDirectory);
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

    private List<SourceExtension> packageImportSourceExtensions(String sourceFile) {
        String normalized = RepoSourcePathUtils.normalizePath(sourceFile);
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
            return List.of(new SourceExtension(
                    RepoSourcePathUtils.directory(RepoSourcePathUtils.normalizeDisplayPath(sourceFile)),
                    ".py"
            ));
        }
        return List.of();
    }

    private List<SourceExtension> relativeImportSourceExtensions(String sourceFile) {
        String normalized = RepoSourcePathUtils.normalizePath(sourceFile);
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

    private record SourceExtension(String sourceRoot, String suffix) {
    }
}
