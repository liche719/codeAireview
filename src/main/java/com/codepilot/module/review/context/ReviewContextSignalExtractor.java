package com.codepilot.module.review.context;

import com.codepilot.module.review.entity.ReviewFile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReviewContextSignalExtractor {

    private static final int LARGE_PR_REVIEWABLE_FILE_THRESHOLD = 10;

    private static final int LARGE_PR_PATCH_CHAR_THRESHOLD = 30000;

    private static final int SEMANTIC_VALUE_LIMIT = 20;

    private static final int REPO_RELATIONSHIP_HINT_LIMIT = 40;

    private static final Pattern JAVA_PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([a-zA-Z_][\\w.]*);");

    private static final Pattern JAVA_IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([a-zA-Z_][\\w.*]*);");

    private static final Pattern JAVA_TYPE_PATTERN = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+([A-Z_$][\\w$]*)\\b"
    );

    private static final Pattern JAVA_METHOD_PATTERN = Pattern.compile(
            "\\b(?:public|protected|private|static|final|synchronized|abstract|native|strictfp|default|\\s)+[\\w<>\\[\\],.?]+\\s+([a-zA-Z_$][\\w$]*)\\s*\\("
    );

    private static final Pattern ANNOTATION_PATTERN = Pattern.compile("@([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)");

    private static final Pattern JS_IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+(?:.+?\\s+from\\s+)?['\"]([^'\"]+)['\"]");

    private static final Pattern JS_EXPORT_PATTERN = Pattern.compile("\\bexport\\s+(?:default\\s+)?(?:class|function|const|let|var|interface|type)\\s+([A-Za-z_$][\\w$]*)");

    private static final Pattern JS_FUNCTION_PATTERN = Pattern.compile("\\b(?:function\\s+([A-Za-z_$][\\w$]*)|(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?\\()");

    private static final Pattern PY_IMPORT_PATTERN = Pattern.compile("^\\s*(?:from\\s+([\\w.]+)\\s+import\\s+.+|import\\s+([\\w.]+))");

    private static final Pattern PY_SYMBOL_PATTERN = Pattern.compile("^\\s*(?:class|def)\\s+([A-Za-z_][\\w]*)\\s*[(:]");

    private static final Pattern API_ROUTE_PATTERN = Pattern.compile(
            "(?:@(?:Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(([^)]*)\\)|\\b(?:router|app)\\.(?:get|post|put|delete|patch)\\s*\\(([^)]*)\\))",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern COMPONENT_ROLE_PATTERN = Pattern.compile(
            "(.+?)(Controller|Service|Repository|Mapper|Client|Handler|Provider|Config|Configuration|Dto|DTO|Request|Response|UseCase)$"
    );

    public List<ReviewContext.FileSummary> fileSummaries(List<ReviewFile> reviewFiles) {
        if (reviewFiles == null || reviewFiles.isEmpty()) {
            return List.of();
        }
        return reviewFiles.stream()
                .filter(reviewFile -> StringUtils.hasText(reviewFile.getFilePath()))
                .map(this::fileSummary)
                .toList();
    }

    public List<ReviewContext.SemanticFileContext> semanticFileContexts(List<ReviewFile> reviewFiles) {
        if (reviewFiles == null || reviewFiles.isEmpty()) {
            return List.of();
        }
        return reviewFiles.stream()
                .filter(reviewFile -> !Boolean.TRUE.equals(reviewFile.getSkipped()))
                .filter(reviewFile -> StringUtils.hasText(reviewFile.getFilePath()))
                .map(this::semanticFileContext)
                .filter(context -> hasSemanticContent(context) || isSourcePath(normalizePath(context.filePath())))
                .toList();
    }

    public List<ReviewContext.RepoRelationshipHint> repoRelationshipHints(
            List<ReviewFile> reviewFiles,
            List<ReviewContext.SemanticFileContext> semanticFileContexts
    ) {
        if (reviewFiles == null || reviewFiles.isEmpty()) {
            return List.of();
        }

        Map<String, ReviewContext.SemanticFileContext> semanticByPath =
                semanticContextByPath(reviewFiles, semanticFileContexts);
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

    private Map<String, ReviewContext.SemanticFileContext> semanticContextByPath(
            List<ReviewFile> reviewFiles,
            List<ReviewContext.SemanticFileContext> semanticFileContexts
    ) {
        List<ReviewContext.SemanticFileContext> contexts = semanticFileContexts == null || semanticFileContexts.isEmpty()
                ? semanticFileContexts(reviewFiles)
                : semanticFileContexts;
        Map<String, ReviewContext.SemanticFileContext> contextsByPath = new LinkedHashMap<>();
        contexts.stream()
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
        return new RelationshipFile(
                filePath,
                normalizePath(filePath),
                directory(normalizePath(filePath)),
                codeIdentity(normalizePath(filePath)),
                baseNameWithoutTestSuffix(normalizePath(filePath)),
                semanticContext == null ? null : semanticContext.packageName(),
                imports,
                declaredSymbols,
                componentRole.domain(),
                componentRole.role(),
                isTestPath(normalizePath(filePath))
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

    private ReviewContext.SemanticFileContext semanticFileContext(ReviewFile reviewFile) {
        String filePath = reviewFile.getFilePath().trim();
        String language = language(filePath);
        String packageName = null;
        Set<String> declaredSymbols = new LinkedHashSet<>();
        Set<String> changedMethods = new LinkedHashSet<>();
        Set<String> annotations = new LinkedHashSet<>();
        Set<String> imports = new LinkedHashSet<>();
        Set<String> apiRoutes = new LinkedHashSet<>();

        for (String line : semanticLines(reviewFile.getPatch())) {
            packageName = firstText(packageName, extractPackageName(language, line));
            declaredSymbols.addAll(extractDeclaredSymbols(language, line));
            changedMethods.addAll(extractChangedMethods(language, line));
            annotations.addAll(extractAnnotations(line));
            imports.addAll(extractImports(language, line));
            apiRoutes.addAll(extractApiRoutes(line));
        }

        return new ReviewContext.SemanticFileContext(
                filePath,
                language,
                packageName,
                limit(declaredSymbols),
                limit(changedMethods),
                limit(annotations),
                limit(imports),
                limit(apiRoutes)
        );
    }

    private boolean hasSemanticContent(ReviewContext.SemanticFileContext context) {
        return StringUtils.hasText(context.packageName())
                || !context.declaredSymbols().isEmpty()
                || !context.changedMethods().isEmpty()
                || !context.annotations().isEmpty()
                || !context.imports().isEmpty()
                || !context.apiRoutes().isEmpty();
    }

    private List<String> semanticLines(String patch) {
        if (!StringUtils.hasText(patch)) {
            return List.of();
        }
        return patch.lines()
                .filter(line -> !line.startsWith("+++"))
                .filter(line -> !line.startsWith("---"))
                .filter(line -> line.startsWith("+") || line.startsWith(" "))
                .map(line -> line.substring(1))
                .filter(StringUtils::hasText)
                .toList();
    }

    private String extractPackageName(String language, String line) {
        if (!"java".equals(language) && !"kotlin".equals(language)) {
            return null;
        }
        Matcher matcher = JAVA_PACKAGE_PATTERN.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    private List<String> extractDeclaredSymbols(String language, String line) {
        Set<String> symbols = new LinkedHashSet<>();
        if ("java".equals(language) || "kotlin".equals(language)) {
            collectGroupMatches(symbols, JAVA_TYPE_PATTERN, line, 1);
        } else if ("typescript".equals(language) || "javascript".equals(language)) {
            collectGroupMatches(symbols, JS_EXPORT_PATTERN, line, 1);
        } else if ("python".equals(language)) {
            collectGroupMatches(symbols, PY_SYMBOL_PATTERN, line, 1);
        }
        return List.copyOf(symbols);
    }

    private List<String> extractChangedMethods(String language, String line) {
        Set<String> methods = new LinkedHashSet<>();
        if ("java".equals(language) || "kotlin".equals(language)) {
            collectGroupMatches(methods, JAVA_METHOD_PATTERN, line, 1);
        } else if ("typescript".equals(language) || "javascript".equals(language)) {
            Matcher matcher = JS_FUNCTION_PATTERN.matcher(line);
            while (matcher.find()) {
                String name = firstText(matcher.group(1), matcher.group(2));
                if (StringUtils.hasText(name)) {
                    methods.add(name);
                }
            }
        } else if ("python".equals(language) && line.stripLeading().startsWith("def ")) {
            collectGroupMatches(methods, PY_SYMBOL_PATTERN, line, 1);
        }
        return List.copyOf(methods);
    }

    private List<String> extractAnnotations(String line) {
        Set<String> annotations = new LinkedHashSet<>();
        collectGroupMatches(annotations, ANNOTATION_PATTERN, line, 1);
        return List.copyOf(annotations);
    }

    private List<String> extractImports(String language, String line) {
        Set<String> imports = new LinkedHashSet<>();
        if ("java".equals(language) || "kotlin".equals(language)) {
            collectGroupMatches(imports, JAVA_IMPORT_PATTERN, line, 1);
        } else if ("typescript".equals(language) || "javascript".equals(language)) {
            collectGroupMatches(imports, JS_IMPORT_PATTERN, line, 1);
        } else if ("python".equals(language)) {
            Matcher matcher = PY_IMPORT_PATTERN.matcher(line);
            if (matcher.find()) {
                String name = firstText(matcher.group(1), matcher.group(2));
                if (StringUtils.hasText(name)) {
                    imports.add(name);
                }
            }
        }
        return List.copyOf(imports);
    }

    private List<String> extractApiRoutes(String line) {
        Set<String> routes = new LinkedHashSet<>();
        Matcher matcher = API_ROUTE_PATTERN.matcher(line);
        while (matcher.find()) {
            String route = firstText(matcher.group(1), matcher.group(2));
            if (StringUtils.hasText(route)) {
                routes.add(route.replace('"', ' ').replace('\'', ' ').trim());
            }
        }
        return List.copyOf(routes);
    }

    private void collectGroupMatches(Set<String> output, Pattern pattern, String line, int group) {
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
            String value = matcher.group(group);
            if (StringUtils.hasText(value)) {
                output.add(value.trim());
            }
        }
    }

    private List<String> limit(Set<String> values) {
        return values.stream()
                .limit(SEMANTIC_VALUE_LIMIT)
                .toList();
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

    private String firstText(String left, String right) {
        return StringUtils.hasText(left) ? left : right;
    }

    private String language(String filePath) {
        String normalized = normalizePath(filePath);
        if (normalized.endsWith(".java")) {
            return "java";
        }
        if (normalized.endsWith(".kt") || normalized.endsWith(".kts")) {
            return "kotlin";
        }
        if (normalized.endsWith(".ts") || normalized.endsWith(".tsx")) {
            return "typescript";
        }
        if (normalized.endsWith(".js") || normalized.endsWith(".jsx") || normalized.endsWith(".mjs")) {
            return "javascript";
        }
        if (normalized.endsWith(".py")) {
            return "python";
        }
        if (normalized.endsWith(".sql")) {
            return "sql";
        }
        if (normalized.endsWith(".yml") || normalized.endsWith(".yaml") || normalized.endsWith(".properties")) {
            return "config";
        }
        return "unknown";
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

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private record RelationshipFile(
            String filePath,
            String normalizedPath,
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
