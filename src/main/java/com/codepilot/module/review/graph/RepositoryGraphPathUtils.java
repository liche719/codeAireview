package com.codepilot.module.review.graph;

import com.codepilot.module.review.classifier.ReviewFileClassifier;
import org.springframework.util.StringUtils;

import java.util.Locale;

final class RepositoryGraphPathUtils {

    private RepositoryGraphPathUtils() {
    }

    static String normalizePath(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
    }

    static String languageFromPath(String path) {
        String normalized = normalizePath(path);
        if (normalized.endsWith(".java")) {
            return "java";
        }
        if (normalized.endsWith(".kt")) {
            return "kotlin";
        }
        if (normalized.endsWith(".ts") || normalized.endsWith(".tsx")) {
            return "typescript";
        }
        if (normalized.endsWith(".js") || normalized.endsWith(".jsx")) {
            return "javascript";
        }
        if (normalized.endsWith(".py")) {
            return "python";
        }
        if (normalized.endsWith(".sql")) {
            return "sql";
        }
        return "unknown";
    }

    static String graphKind(String path) {
        if (ReviewFileClassifier.isDatabasePath(path)) {
            return "database";
        }
        if (ReviewFileClassifier.isSecuritySensitivePath(path)) {
            return "security";
        }
        if (ReviewFileClassifier.isPublicApiPath(path)) {
            return "api";
        }
        if (ReviewFileClassifier.isConfigurationPath(path)) {
            return "configuration";
        }
        if (ReviewFileClassifier.isDependencyManifestPath(path)) {
            return "dependency";
        }
        if (ReviewFileClassifier.isTestPath(path)) {
            return "test";
        }
        if (ReviewFileClassifier.isDocumentationPath(path)) {
            return "documentation";
        }
        if (ReviewFileClassifier.isProductionCodePath(path)) {
            return "production";
        }
        return "other";
    }
}
