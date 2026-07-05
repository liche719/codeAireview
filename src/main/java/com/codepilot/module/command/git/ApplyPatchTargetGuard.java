package com.codepilot.module.command.git;

import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class ApplyPatchTargetGuard {

    private ApplyPatchTargetGuard() {
    }

    static Path resolveTargetPath(Path repoRoot, String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            throw new IllegalStateException("Patch file path is missing");
        }
        Path normalizedRoot = repoRoot.toAbsolutePath().normalize();
        Path targetPath = normalizedRoot.resolve(relativePath.trim()).normalize();
        if (!targetPath.startsWith(normalizedRoot)) {
            throw new IllegalStateException("Patch path escapes repository root: " + relativePath);
        }
        return targetPath;
    }

    static void ensureRealPathWithinRoot(Path repoRoot, Path targetPath) throws IOException {
        Path normalizedRoot = repoRoot.toAbsolutePath().normalize();
        Path rootRealPath = normalizedRoot.toRealPath();
        Path normalizedTarget = targetPath.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            throw new IllegalStateException("Patch path escapes repository root: " + normalizedTarget);
        }

        Path realTarget;
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            realTarget = normalizedTarget.toRealPath();
        } else {
            Path existingParent = nearestExistingParent(normalizedRoot, normalizedTarget);
            Path realParent = existingParent.toRealPath();
            Path remaining = existingParent.relativize(normalizedTarget);
            realTarget = realParent.resolve(remaining).normalize();
        }

        if (!realTarget.startsWith(rootRealPath)) {
            throw new IllegalStateException("Patch path escapes repository root through a symbolic link: "
                    + normalizedRoot.relativize(normalizedTarget));
        }
    }

    private static Path nearestExistingParent(Path repoRoot, Path targetPath) {
        Path current = targetPath;
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.getParent();
        }
        if (current == null || !current.startsWith(repoRoot)) {
            throw new IllegalStateException("Patch path has no existing parent inside repository root: " + targetPath);
        }
        return current;
    }
}
