package com.codepilot.module.command.fix;

public record FixPatchArtifact(
        String payload,
        String sha256,
        String allowedPathsJson
) {
}
