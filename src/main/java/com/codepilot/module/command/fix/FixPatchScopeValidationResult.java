package com.codepilot.module.command.fix;

import java.util.Set;

public record FixPatchScopeValidationResult(int filesChanged, int changedLines, Set<String> paths) {

    @Override
    public String toString() {
        return "filesChanged=" + filesChanged + ", changedLines=" + changedLines;
    }
}
