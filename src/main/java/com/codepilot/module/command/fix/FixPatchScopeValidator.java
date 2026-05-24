package com.codepilot.module.command.fix;

import com.codepilot.module.command.config.GithubCommandProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class FixPatchScopeValidator {

    private static final Set<String> BLOCKED_FIX_PATHS = Set.of(
            ".env",
            ".env.local",
            ".env.production",
            "docker-compose.yml",
            "docker-compose.server.yml",
            "dockerfile",
            "pom.xml"
    );

    private static final List<String> BLOCKED_FIX_PATH_PREFIXES = List.of(
            ".github/",
            ".git/",
            "src/main/resources/db/migration/"
    );

    private final GithubCommandProperties properties;

    public FixPatchScopeValidationResult validate(String patch, Set<String> allowedPaths) {
        FixPatchScopeValidationResult stats = parsePatchStats(patch);
        if (stats.filesChanged() > properties.getFixMaxFiles()) {
            throw new NonRetryableFixTaskException("Generated patch changes too many files: " + stats.filesChanged());
        }
        if (stats.changedLines() > properties.getFixMaxChangedLines()) {
            throw new NonRetryableFixTaskException("Generated patch changes too many lines: " + stats.changedLines());
        }
        if (stats.paths().isEmpty()) {
            throw new NonRetryableFixTaskException("Generated patch does not declare modified file paths.");
        }

        Set<String> normalizedAllowedPaths = normalizeAllowedPaths(allowedPaths);
        for (String path : stats.paths()) {
            if (isUnsafePatchPath(path)) {
                throw new NonRetryableFixTaskException("Generated patch contains an unsafe file path: " + path);
            }
            if (isBlockedFixPath(path)) {
                throw new NonRetryableFixTaskException("Automatic fixes cannot modify sensitive path: " + path);
            }
            if (!normalizedAllowedPaths.isEmpty() && !normalizedAllowedPaths.contains(path)) {
                throw new NonRetryableFixTaskException("Automatic fixes can only modify selected issue files: " + path);
            }
        }
        return stats;
    }

    private FixPatchScopeValidationResult parsePatchStats(String patch) {
        Set<String> files = new LinkedHashSet<>();
        int changedLines = 0;
        if (!StringUtils.hasText(patch)) {
            return new FixPatchScopeValidationResult(0, 0, Set.of());
        }
        for (String line : patch.split("\\R")) {
            if (line.startsWith("diff --git ")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    addPatchPath(files, parts[2]);
                    addPatchPath(files, parts[3]);
                }
            }
            if (line.startsWith("+++ ")) {
                String filePath = line.substring(4).trim();
                if (!"/dev/null".equals(filePath)) {
                    addPatchPath(files, filePath);
                }
            }
            if (line.startsWith("--- ")) {
                String filePath = line.substring(4).trim();
                if (!"/dev/null".equals(filePath)) {
                    addPatchPath(files, filePath);
                }
            }
            if ((line.startsWith("+") && !line.startsWith("+++"))
                    || (line.startsWith("-") && !line.startsWith("---"))) {
                changedLines++;
            }
        }
        Set<String> orderedFiles = Collections.unmodifiableSet(new LinkedHashSet<>(files));
        return new FixPatchScopeValidationResult(
                files.isEmpty() && changedLines > 0 ? 1 : files.size(),
                changedLines,
                orderedFiles
        );
    }

    private void addPatchPath(Set<String> files, String rawPath) {
        String path = normalizePatchPath(rawPath);
        if ("/dev/null".equals(path)) {
            return;
        }
        if (StringUtils.hasText(path)) {
            files.add(path);
        }
    }

    private Set<String> normalizeAllowedPaths(Set<String> allowedPaths) {
        Set<String> normalized = new LinkedHashSet<>();
        if (allowedPaths == null) {
            return normalized;
        }
        for (String allowedPath : allowedPaths) {
            String path = normalizePatchPath(allowedPath);
            if (StringUtils.hasText(path)) {
                normalized.add(path);
            }
        }
        return normalized;
    }

    private boolean isBlockedFixPath(String path) {
        String normalized = normalizePatchPath(path);
        if (!StringUtils.hasText(normalized)) {
            return true;
        }
        String lowerCase = normalized.toLowerCase(Locale.ROOT);
        if (BLOCKED_FIX_PATHS.contains(lowerCase)) {
            return true;
        }
        return BLOCKED_FIX_PATH_PREFIXES.stream().anyMatch(lowerCase::startsWith);
    }

    private boolean isUnsafePatchPath(String path) {
        String normalized = normalizePatchPath(path);
        String lowerCase = normalized.toLowerCase(Locale.ROOT);
        return !StringUtils.hasText(normalized)
                || normalized.startsWith("/")
                || lowerCase.matches("^[a-z]:/.*")
                || "..".equals(normalized)
                || normalized.startsWith("../")
                || normalized.endsWith("/..")
                || normalized.contains("/../");
    }

    private String normalizePatchPath(String rawPath) {
        if (!StringUtils.hasText(rawPath)) {
            return "";
        }
        String path = rawPath.trim().replace('\\', '/');
        if (path.startsWith("\"") && path.endsWith("\"") && path.length() >= 2) {
            path = path.substring(1, path.length() - 1);
        }
        if (path.startsWith("a/") || path.startsWith("b/")) {
            path = path.substring(2);
        }
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        return path;
    }
}
