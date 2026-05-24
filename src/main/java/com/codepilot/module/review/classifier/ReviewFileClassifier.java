package com.codepilot.module.review.classifier;

import java.util.Locale;

public final class ReviewFileClassifier {

    private ReviewFileClassifier() {
    }

    public static boolean isSourcePath(String path) {
        String normalized = normalizePath(path);
        return normalized.startsWith("src/main/")
                || normalized.endsWith(".java")
                || normalized.endsWith(".kt")
                || normalized.endsWith(".go")
                || normalized.endsWith(".ts")
                || normalized.endsWith(".tsx")
                || normalized.endsWith(".js")
                || normalized.endsWith(".jsx")
                || normalized.endsWith(".py");
    }

    public static boolean isProductionCodePath(String path) {
        return isSourcePath(path) && !isTestPath(path);
    }

    public static boolean isTestPath(String path) {
        String normalized = normalizePath(path);
        return normalized.contains("/test/")
                || normalized.contains("/tests/")
                || normalized.endsWith("test.java")
                || normalized.endsWith("tests.java")
                || normalized.endsWith(".spec.ts")
                || normalized.endsWith(".test.ts")
                || normalized.endsWith(".spec.tsx")
                || normalized.endsWith(".test.tsx")
                || normalized.endsWith(".spec.js")
                || normalized.endsWith(".test.js");
    }

    public static boolean isDatabasePath(String path) {
        String normalized = normalizePath(path);
        return normalized.contains("/db/migration/")
                || normalized.contains("/migrations/")
                || normalized.endsWith(".sql")
                || normalized.endsWith("mapper.xml");
    }

    public static boolean isSecuritySensitivePath(String path) {
        String normalized = normalizePath(path);
        return normalized.contains("security")
                || normalized.contains("auth")
                || normalized.contains("permission")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("credential");
    }

    public static boolean isConfigurationPath(String path) {
        String normalized = normalizePath(path);
        return normalized.endsWith(".yml")
                || normalized.endsWith(".yaml")
                || normalized.endsWith(".properties")
                || normalized.endsWith(".toml")
                || normalized.endsWith(".env")
                || normalized.endsWith("package.json")
                || normalized.endsWith("tsconfig.json")
                || normalized.endsWith(".eslintrc.json")
                || normalized.contains("config.json")
                || normalized.startsWith(".github/workflows/")
                || normalized.equals("dockerfile")
                || normalized.endsWith("/dockerfile")
                || normalized.contains("docker-compose");
    }

    public static boolean isDependencyManifestPath(String path) {
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

    public static boolean isPublicApiPath(String path) {
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

    public static boolean isDocumentationPath(String path) {
        String normalized = normalizePath(path);
        return normalized.endsWith(".md")
                || normalized.endsWith(".adoc")
                || normalized.endsWith(".rst")
                || normalized.startsWith("docs/");
    }

    public static String fileName(String path) {
        String normalized = normalizePath(path);
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    public static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
    }
}
