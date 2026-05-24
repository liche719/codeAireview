package com.codepilot.module.agent.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

@Component
public class AiReviewResultSchemaValidator {

    private static final Set<String> ROOT_FIELDS = Set.of("issues", "summary");

    private static final Set<String> ISSUE_FIELDS = Set.of(
            "filePath",
            "lineNumber",
            "issueType",
            "issueTypeZh",
            "severity",
            "title",
            "description",
            "suggestion",
            "source",
            "ruleReference"
    );

    private static final Set<String> REQUIRED_TEXT_ISSUE_FIELDS = Set.of(
            "issueType",
            "issueTypeZh",
            "severity",
            "title",
            "description",
            "suggestion",
            "source"
    );

    private static final Set<String> ISSUE_TYPES = Set.of(
            "BUG_RISK",
            "SECURITY",
            "PERFORMANCE",
            "STYLE",
            "SQL_RISK",
            "EXCEPTION_HANDLING",
            "LOGGING",
            "TEST_MISSING"
    );

    private static final Set<String> SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private static final Set<String> SOURCES = Set.of("LLM", "TOOL");

    public void validate(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("AI review result JSON must be an object");
        }
        validateRootFields(root);
        validateSummary(root.get("summary"));
        validateIssues(root.get("issues"));
    }

    private void validateRootFields(JsonNode root) {
        Set<String> actualFields = fieldNames(root);
        if (!actualFields.equals(ROOT_FIELDS)) {
            throw new IllegalArgumentException("AI review result JSON must contain only issues and summary");
        }
    }

    private void validateSummary(JsonNode summary) {
        if (summary == null || summary.isNull() || !summary.isTextual()) {
            throw new IllegalArgumentException("AI review result summary must be a string");
        }
    }

    private void validateIssues(JsonNode issues) {
        if (issues == null || issues.isNull()) {
            throw new IllegalArgumentException("AI review result JSON must contain issues array");
        }
        if (!issues.isArray()) {
            throw new IllegalArgumentException("AI review result issues must be an array");
        }
        for (JsonNode issue : issues) {
            validateIssue(issue);
        }
    }

    private void validateIssue(JsonNode issue) {
        if (!issue.isObject()) {
            throw new IllegalArgumentException("AI review result issues must contain objects only");
        }
        Set<String> actualFields = fieldNames(issue);
        if (!actualFields.equals(ISSUE_FIELDS)) {
            throw new IllegalArgumentException("AI review issue must contain only supported fields");
        }
        for (String field : REQUIRED_TEXT_ISSUE_FIELDS) {
            JsonNode value = issue.get(field);
            if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
                throw new IllegalArgumentException("AI review issue field must be a non-empty string: " + field);
            }
        }
        validateOptionalText(issue, "filePath");
        validateOptionalText(issue, "ruleReference");
        validateOptionalInteger(issue, "lineNumber");
        validateEnum(issue, "issueType", ISSUE_TYPES);
        validateEnum(issue, "severity", SEVERITIES);
        validateEnum(issue, "source", SOURCES);
    }

    private void validateOptionalText(JsonNode issue, String field) {
        JsonNode value = issue.get(field);
        if (value != null && !value.isNull() && !value.isTextual()) {
            throw new IllegalArgumentException("AI review issue field must be a string or null: " + field);
        }
    }

    private void validateOptionalInteger(JsonNode issue, String field) {
        JsonNode value = issue.get(field);
        if (value != null && !value.isNull() && !value.canConvertToInt()) {
            throw new IllegalArgumentException("AI review issue field must be an integer or null: " + field);
        }
    }

    private void validateEnum(JsonNode issue, String field, Set<String> allowedValues) {
        String value = issue.get(field).asText();
        if (!allowedValues.contains(value)) {
            throw new IllegalArgumentException("AI review issue field has invalid value: " + field);
        }
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        Iterator<String> iterator = node.fieldNames();
        iterator.forEachRemaining(names::add);
        return names;
    }
}
