package com.codepilot.module.command.parser;

import com.codepilot.module.command.dto.GithubCommandIntentResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Iterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class GithubCommandIntentResultParser {

    private static final Pattern JSON_FENCE_PATTERN =
            Pattern.compile("^```(?:json)?\\s*(.*?)\\s*```$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Set<String> ALLOWED_FIELDS = Set.of("type", "dryRun", "reason");

    private static final Set<String> ALLOWED_TYPES = Set.of("REVIEW", "FIX", "CHAT", "UNKNOWN");

    private final ObjectMapper objectMapper;

    public GithubCommandIntentResult parse(String responseText) {
        if (!StringUtils.hasText(responseText)) {
            return null;
        }
        try {
            String json = stripCodeFence(responseText.trim());
            JsonNode root = objectMapper.readTree(json);
            validateSchema(root);
            return objectMapper.treeToValue(root, GithubCommandIntentResult.class);
        } catch (Exception exception) {
            log.warn("Failed to parse GitHub command intent JSON, message={}", exception.getMessage());
            return null;
        }
    }

    private void validateSchema(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("GitHub command intent JSON must be an object");
        }
        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!ALLOWED_FIELDS.contains(fieldName)) {
                throw new IllegalArgumentException("GitHub command intent JSON must contain only type, dryRun and reason");
            }
        }
        JsonNode type = root.get("type");
        if (type == null || !type.isTextual() || !ALLOWED_TYPES.contains(type.asText().trim().toUpperCase())) {
            throw new IllegalArgumentException("GitHub command intent type is invalid");
        }
        JsonNode dryRun = root.get("dryRun");
        if (dryRun != null && !dryRun.isBoolean()) {
            throw new IllegalArgumentException("GitHub command intent dryRun must be boolean");
        }
        JsonNode reason = root.get("reason");
        if (reason != null && !reason.isTextual() && !reason.isNull()) {
            throw new IllegalArgumentException("GitHub command intent reason must be a string");
        }
    }

    private String stripCodeFence(String content) {
        Matcher matcher = JSON_FENCE_PATTERN.matcher(content);
        return matcher.matches() ? matcher.group(1).trim() : content;
    }
}
