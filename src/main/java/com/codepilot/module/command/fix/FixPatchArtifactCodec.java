package com.codepilot.module.command.fix;

import com.codepilot.module.command.config.GithubCommandProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class FixPatchArtifactCodec {

    private final ObjectMapper objectMapper;

    private final GithubCommandProperties properties;

    public FixPatchArtifact encode(String patch, Set<String> allowedPaths) {
        if (!StringUtils.hasText(patch)) {
            throw new IllegalArgumentException("patch payload is required");
        }
        if (patch.length() > properties.getFixMaxPatchChars()) {
            throw new IllegalArgumentException("patch payload exceeds configured character limit");
        }
        try {
            Set<String> safePaths = allowedPaths == null ? Set.of() : new LinkedHashSet<>(allowedPaths);
            return new FixPatchArtifact(
                    patch,
                    sha256(patch),
                    objectMapper.writeValueAsString(safePaths)
            );
        } catch (Exception exception) {
            throw new IllegalArgumentException("failed to encode fix patch artifact", exception);
        }
    }

    public Set<String> decodeAllowedPaths(String json) {
        if (!StringUtils.hasText(json)) {
            return Set.of();
        }
        try {
            Set<String> paths = objectMapper.readValue(json, new TypeReference<LinkedHashSet<String>>() {
            });
            return paths == null ? Set.of() : paths;
        } catch (Exception exception) {
            throw new IllegalArgumentException("failed to decode approved fix paths", exception);
        }
    }

    public boolean matches(String patch, String expectedSha256) {
        return StringUtils.hasText(patch)
                && StringUtils.hasText(expectedSha256)
                && MessageDigest.isEqual(
                sha256(patch).getBytes(StandardCharsets.US_ASCII),
                expectedSha256.getBytes(StandardCharsets.US_ASCII));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
