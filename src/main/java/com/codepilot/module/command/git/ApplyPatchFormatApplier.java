package com.codepilot.module.command.git;

import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ApplyPatchFormatApplier {

    private ApplyPatchFormatApplier() {
    }

    public static boolean isApplyPatchFormat(String patch) {
        return ApplyPatchParser.isApplyPatchFormat(patch);
    }

    public static ApplyPatchApplicationResult apply(Path repoRoot, String patch) throws IOException {
        if (repoRoot == null) {
            throw new IllegalArgumentException("repoRoot is required");
        }
        if (!StringUtils.hasText(patch)) {
            throw new IllegalArgumentException("patch is required");
        }

        Path normalizedRoot = repoRoot.toAbsolutePath().normalize();
        List<ApplyPatchSection> sections = ApplyPatchParser.parseSections(patch);
        int changedFiles = 0;
        List<String> changedPaths = new ArrayList<>();

        for (ApplyPatchSection section : sections) {
            boolean changed = ApplyPatchSectionApplier.apply(normalizedRoot, section);
            if (changed) {
                changedFiles++;
                changedPaths.add(section.path());
            }
        }

        return new ApplyPatchApplicationResult(changedFiles, changedPaths);
    }

    public record ApplyPatchApplicationResult(int changedFiles, List<String> changedPaths) {

        public ApplyPatchApplicationResult {
            changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
        }
    }
}
