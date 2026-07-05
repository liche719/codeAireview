package com.codepilot.module.review.context;

import org.springframework.util.StringUtils;

import java.util.Locale;

final class RepoSourcePathUtils {

    private RepoSourcePathUtils() {
    }

    static boolean isSafeRelativePath(String path) {
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

    static boolean isSupportedSourcePath(String path) {
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

    static boolean isJvmSourcePath(String path) {
        String normalized = normalizePath(path);
        return normalized.endsWith(".java")
                || normalized.endsWith(".kt")
                || normalized.endsWith(".kts");
    }

    static String normalizePath(String path) {
        return normalizeDisplayPath(path).toLowerCase(Locale.ROOT);
    }

    static String normalizeDisplayPath(String path) {
        return path == null ? "" : path.replace('\\', '/').trim().replaceAll("/{2,}", "/");
    }

    static String directory(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        int index = path.lastIndexOf('/');
        return index < 0 ? "" : path.substring(0, index);
    }
}
