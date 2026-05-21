package com.codepilot.module.agent.parser;

import com.codepilot.module.agent.dto.CodeFixResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class CodeFixResultParser {

    private static final Set<String> REQUIRED_FIELDS = Set.of("summary", "patch", "commitMessage");

    private final ObjectMapper objectMapper;

    public CodeFixResult parse(String responseText) {
        if (!StringUtils.hasText(responseText)) {
            return CodeFixResult.empty();
        }
        String content = responseText.trim();
        try {
            String json = extractJson(content);
            if (StringUtils.hasText(json)) {
                JsonNode root = objectMapper.readTree(json);
                validateSchema(root);
                return objectMapper.treeToValue(root, CodeFixResult.class);
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to parse code fix result as JSON", exception);
        }
        throw new IllegalArgumentException("Code fix result must be a JSON object");
    }

    private String extractJson(String content) {
        String stripped = stripCodeFence(content);
        int start = stripped.indexOf('{');
        int end = stripped.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return stripped.substring(start, end + 1);
        }
        return null;
    }

    private void validateSchema(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Code fix result JSON must be an object");
        }
        java.util.Iterator<String> fieldNames = root.fieldNames();
        java.util.Set<String> actualFields = new java.util.HashSet<>();
        fieldNames.forEachRemaining(actualFields::add);
        if (!actualFields.equals(REQUIRED_FIELDS)) {
            throw new IllegalArgumentException("Code fix result JSON must contain only summary, patch and commitMessage");
        }
        for (String field : REQUIRED_FIELDS) {
            JsonNode value = root.get(field);
            if (value == null || value.isNull() || !value.isTextual()) {
                throw new IllegalArgumentException("Code fix result field must be a string: " + field);
            }
        }
        String patch = root.get("patch").asText();
        String commitMessage = root.get("commitMessage").asText();
        if (StringUtils.hasText(patch) && !StringUtils.hasText(commitMessage)) {
            throw new IllegalArgumentException("Code fix result commitMessage is required when patch is not empty");
        }
        if (StringUtils.hasText(commitMessage) && commitMessage.contains("\n")) {
            throw new IllegalArgumentException("Code fix result commitMessage must be a single line");
        }
    }

    private String stripCodeFence(String content) {
        return content
                .replaceFirst("(?is)^```(?:json|diff)?\\s*", "")
                .replaceFirst("(?is)\\s*```$", "")
                .trim();
    }
}
