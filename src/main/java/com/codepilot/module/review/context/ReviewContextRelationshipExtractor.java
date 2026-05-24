package com.codepilot.module.review.context;

import com.codepilot.module.review.entity.ReviewFile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReviewContextRelationshipExtractor {

    private static final int REPO_RELATIONSHIP_HINT_LIMIT = 40;

    private static final Pattern COMPONENT_ROLE_PATTERN = Pattern.compile(
            "(.+?)(Controller|Service|Repository|Mapper|Client|Handler|Provider|Config|Configuration|Dto|DTO|Request|Response|UseCase)$"
    );

    public List<ReviewContext.RepoRelationshipHint> repoRelationshipHints(
            List<ReviewFile> reviewFiles,
            List<ReviewContext.SemanticFileContext> semanticFileContexts
    ) {
        if (reviewFiles == null || reviewFiles.isEmpty()) {
            return List.of();
        }

        Map<String, ReviewContext.SemanticFileContext> semanticByPath = semanticContextByPath(semanticFileContexts);
        List<RelationshipFile> files = reviewFiles.stream()
                .filter(reviewFile -> !Boolean.TRUE.equals(reviewFile.getSkipped()))
                .filter(reviewFile -> StringUtils.hasText(reviewFile.getFilePath()))
                .map(reviewFile -> relationshipFile(reviewFile.getFilePath().trim(), semanticByPath))
                .toList();
        if (files.size() < 2) {
            return List.of();
        }

        Map<String, ReviewContext.RepoRelationshipHint> hints = new LinkedHashMap<>();
        for (int leftIndex = 0; leftIndex < files.size(); leftIndex++) {
            for (int rightIndex = leftIndex + 1; rightIndex < files.size(); rightIndex++) {
                RelationshipFile left = files.get(leftIndex);
                RelationshipFile right = files.get(rightIndex);
                addHint(hints, importTargetHint(left, right));
                addHint(hints, importTargetHint(right, left));
                addHint(hints, sourceTestPairHint(left, right));
                addHint(hints, layeredComponentHint(left, right));
                addHint(hints, samePackageHint(left, right));
                addHint(hints, sharedImportHint(left, right));
                if (hints.size() >= REPO_RELATIONSHIP_HINT_LIMIT) {
                    return List.copyOf(hints.values());
                }
            }
        }
        return List.copyOf(hints.values());
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

    private RelationshipFile relationshipFile(
            String filePath,
            Map<String, ReviewContext.SemanticFileContext> semanticByPath
    ) {
        ReviewContext.SemanticFileContext semanticContext = semanticByPath.get(normalizePath(filePath));
        Set<String> imports = semanticContext == null ? Set.of() : new LinkedHashSet<>(semanticContext.imports());
        Set<String> declaredSymbols = semanticContext == null
                ? Set.of()
                : new LinkedHashSet<>(semanticContext.declaredSymbols());
        ComponentRole componentRole = componentRole(filePath, declaredSymbols);
        String normalizedPath = normalizePath(filePath);
        return new RelationshipFile(
                filePath,
                directory(normalizedPath),
                codeIdentity(normalizedPath),
                baseNameWithoutTestSuffix(normalizedPath),
                semanticContext == null ? null : semanticContext.packageName(),
                imports,
                declaredSymbols,
                componentRole.domain(),
                componentRole.role(),
                isTestPath(normalizedPath)
        );
    }

    private ReviewContext.RepoRelationshipHint importTargetHint(RelationshipFile source, RelationshipFile target) {
        if (source.imports().isEmpty()) {
            return null;
        }
        String matchedImport = source.imports().stream()
                .filter(importValue -> importsTarget(source, target, importValue))
                .findFirst()
                .orElse(null);
        if (!StringUtils.hasText(matchedImport)) {
            return null;
        }
        return new ReviewContext.RepoRelationshipHint(
                source.filePath(),
                target.filePath(),
                "IMPORT_TARGET",
                "Source imports target changed file via '" + matchedImport + "'; inspect cross-file API compatibility."
        );
    }

    private boolean importsTarget(RelationshipFile source, RelationshipFile target, String importValue) {
        if (!StringUtils.hasText(importValue)) {
            return false;
        }
        String normalizedImport = importValue.trim();
        if (StringUtils.hasText(target.packageName())) {
            if (normalizedImport.equals(target.packageName())
                    || normalizedImport.equals(target.packageName() + ".*")
                    || normalizedImport.startsWith(target.packageName() + ".")) {
                if (target.declaredSymbols().isEmpty()) {
                    return true;
                }
                return target.declaredSymbols().stream()
                        .anyMatch(symbol -> normalizedImport.equals(target.packageName() + "." + symbol)
                                || normalizedImport.endsWith("." + symbol));
            }
        }
        String normalizedPathImport = normalizeImportPath(source, normalizedImport);
        return StringUtils.hasText(normalizedPathImport)
                && (normalizedPathImport.endsWith("/" + target.baseName())
                || normalizedPathImport.endsWith("/" + target.baseName().toLowerCase(Locale.ROOT))
                || normalizedPathImport.equals(target.baseName())
                || normalizedPathImport.equals(target.baseName().toLowerCase(Locale.ROOT)));
    }

    private ReviewContext.RepoRelationshipHint sourceTestPairHint(RelationshipFile left, RelationshipFile right) {
        if (!left.codeIdentity().equals(right.codeIdentity()) || left.testPath() == right.testPath()) {
            return null;
        }
        RelationshipFile source = left.testPath() ? right : left;
        RelationshipFile test = left.testPath() ? left : right;
        return new ReviewContext.RepoRelationshipHint(
                source.filePath(),
                test.filePath(),
                "SOURCE_TEST_PAIR",
                "Source and matching test changed together; verify coverage matches the behavior change."
        );
    }

    private ReviewContext.RepoRelationshipHint layeredComponentHint(RelationshipFile left, RelationshipFile right) {
        if (!StringUtils.hasText(left.componentDomain())
                || !left.componentDomain().equalsIgnoreCase(right.componentDomain())
                || !StringUtils.hasText(left.componentRole())
                || !StringUtils.hasText(right.componentRole())
                || left.componentRole().equalsIgnoreCase(right.componentRole())) {
            return null;
        }
        return new ReviewContext.RepoRelationshipHint(
                left.filePath(),
                right.filePath(),
                "LAYERED_COMPONENT",
                "Shared domain prefix '" + left.componentDomain() + "' across "
                        + left.componentRole() + " and " + right.componentRole()
                        + " layers; check contract and responsibility drift."
        );
    }

    private ReviewContext.RepoRelationshipHint samePackageHint(RelationshipFile left, RelationshipFile right) {
        if (!StringUtils.hasText(left.packageName())
                || !left.packageName().equals(right.packageName())) {
            return null;
        }
        return new ReviewContext.RepoRelationshipHint(
                left.filePath(),
                right.filePath(),
                "SAME_PACKAGE",
                "Both patch contexts declare package '" + left.packageName()
                        + "'; check package-level coupling and side effects."
        );
    }

    private ReviewContext.RepoRelationshipHint sharedImportHint(RelationshipFile left, RelationshipFile right) {
        List<String> sharedImports = left.imports().stream()
                .filter(right.imports()::contains)
                .filter(this::isMeaningfulSharedImport)
                .limit(3)
                .toList();
        if (sharedImports.isEmpty()) {
            return null;
        }
        return new ReviewContext.RepoRelationshipHint(
                left.filePath(),
                right.filePath(),
                "SHARED_IMPORT",
                "Both files changed around shared dependency/import(s): " + String.join(", ", sharedImports)
        );
    }

    private void addHint(
            Map<String, ReviewContext.RepoRelationshipHint> hints,
            ReviewContext.RepoRelationshipHint hint
    ) {
        if (hint == null || hints.size() >= REPO_RELATIONSHIP_HINT_LIMIT) {
            return;
        }
        hints.putIfAbsent(hint.sourceFile() + "\u0000" + hint.targetFile() + "\u0000" + hint.type(), hint);
    }

    private boolean isMeaningfulSharedImport(String importValue) {
        if (!StringUtils.hasText(importValue)) {
            return false;
        }
        String normalized = importValue.trim().toLowerCase(Locale.ROOT);
        return !normalized.startsWith("java.")
                && !normalized.startsWith("javax.")
                && !normalized.startsWith("jakarta.")
                && !normalized.startsWith("org.junit.")
                && !normalized.startsWith("org.assertj.")
                && !normalized.startsWith("react")
                && !normalized.startsWith("@testing-library/");
    }

    private String normalizeImportPath(RelationshipFile source, String importValue) {
        if (!StringUtils.hasText(importValue)) {
            return "";
        }
        String normalizedImport = importValue.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
        if (!normalizedImport.startsWith(".")) {
            return normalizedImport.replace('.', '/');
        }
        String currentDirectory = source.directory();
        while (normalizedImport.startsWith("../")) {
            currentDirectory = directory(currentDirectory);
            normalizedImport = normalizedImport.substring(3);
        }
        if (normalizedImport.startsWith("./")) {
            normalizedImport = normalizedImport.substring(2);
        }
        if (!StringUtils.hasText(currentDirectory)) {
            return normalizedImport;
        }
        return currentDirectory + "/" + normalizedImport;
    }

    private ComponentRole componentRole(String filePath, Set<String> declaredSymbols) {
        Set<String> candidates = new LinkedHashSet<>(declaredSymbols);
        candidates.add(baseNameWithoutTestSuffix(normalizePath(filePath)));
        for (String candidate : candidates) {
            Matcher matcher = COMPONENT_ROLE_PATTERN.matcher(candidate);
            if (matcher.matches()) {
                String domain = matcher.group(1);
                String role = matcher.group(2);
                if (StringUtils.hasText(domain) && StringUtils.hasText(role)) {
                    return new ComponentRole(domain, role);
                }
            }
        }
        return new ComponentRole(null, null);
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

    private String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
    }

    private String fileName(String path) {
        String normalized = normalizePath(path);
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    private String directory(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
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
        String name = fileName(normalized)
                .replaceFirst("\\.[^.]+$", "");
        return name
                .replaceFirst("(?i)(test|tests|spec)$", "")
                .replaceFirst("(?i)\\.(test|spec)$", "");
    }

    private record RelationshipFile(
            String filePath,
            String directory,
            String codeIdentity,
            String baseName,
            String packageName,
            Set<String> imports,
            Set<String> declaredSymbols,
            String componentDomain,
            String componentRole,
            boolean testPath
    ) {
    }

    private record ComponentRole(String domain, String role) {
    }
}
