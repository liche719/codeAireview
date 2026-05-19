package com.codepilot.module.agent.parser;

import com.codepilot.module.agent.dto.CodeFixResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class CodeFixResultParser {

    private final ObjectMapper objectMapper;

    public CodeFixResult parse(String responseText) {
        if (!StringUtils.hasText(responseText)) {
            return CodeFixResult.empty();
        }
        String content = responseText.trim();
        try {
            String json = extractJson(content);
            if (StringUtils.hasText(json)) {
                return objectMapper.readValue(json, CodeFixResult.class);
            }
        } catch (Exception exception) {
            log.warn("Failed to parse code fix JSON, fallback to raw diff, message={}", exception.getMessage());
        }
        if (content.contains("diff --git") || content.contains("@@")) {
            CodeFixResult result = new CodeFixResult();
            result.setSummary("Generated unified diff.");
            result.setPatch(stripCodeFence(content));
            return result;
        }
        return CodeFixResult.empty();
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

    private String stripCodeFence(String content) {
        return content
                .replaceFirst("(?is)^```(?:json|diff)?\\s*", "")
                .replaceFirst("(?is)\\s*```$", "")
                .trim();
    }
}
