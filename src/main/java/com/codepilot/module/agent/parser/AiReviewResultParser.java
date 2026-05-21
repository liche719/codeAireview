package com.codepilot.module.agent.parser;

import com.codepilot.module.agent.dto.AiReviewResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class AiReviewResultParser {

    private static final Pattern JSON_FENCE_PATTERN = Pattern.compile("^```(?:json)?\\s*(.*?)\\s*```$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    public AiReviewResult parse(String content) {
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("AI review result is empty");
        }

        String normalizedContent = normalize(content);
        try {
            JsonNode root = objectMapper.readTree(normalizedContent);
            validateSchema(root);
            AiReviewResult result = objectMapper.treeToValue(root, AiReviewResult.class);
            if (result.getIssues() == null) {
                result.setIssues(new java.util.ArrayList<>());
            }
            return result;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to parse AI review result as JSON", exception);
        }
    }

    private String normalize(String content) {
        String trimmed = content.trim();
        Matcher matcher = JSON_FENCE_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            return matcher.group(1).trim();
        }
        int jsonStart = trimmed.indexOf('{');
        int jsonEnd = trimmed.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return trimmed.substring(jsonStart, jsonEnd + 1).trim();
        }
        return trimmed;
    }

    private void validateSchema(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("AI review result JSON must be an object");
        }
        JsonNode issues = root.get("issues");
        if (issues == null || issues.isNull()) {
            throw new IllegalArgumentException("AI review result JSON must contain issues array");
        }
        if (!issues.isArray()) {
            throw new IllegalArgumentException("AI review result issues must be an array");
        }
        for (JsonNode issue : issues) {
            if (!issue.isObject()) {
                throw new IllegalArgumentException("AI review result issues must contain objects only");
            }
        }
    }
}
