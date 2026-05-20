package com.codepilot.module.command.parser;

import com.codepilot.module.command.dto.GithubCommandIntentResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class GithubCommandIntentResultParser {

    private final ObjectMapper objectMapper;

    public GithubCommandIntentResult parse(String responseText) {
        if (!StringUtils.hasText(responseText)) {
            return null;
        }
        String content = responseText.trim();
        try {
            String json = extractJson(stripCodeFence(content));
            if (StringUtils.hasText(json)) {
                return objectMapper.readValue(json, GithubCommandIntentResult.class);
            }
        } catch (Exception exception) {
            log.warn("Failed to parse GitHub command intent JSON, message={}", exception.getMessage());
        }
        return null;
    }

    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return null;
    }

    private String stripCodeFence(String content) {
        return content
                .replaceFirst("(?is)^```(?:json)?\\s*", "")
                .replaceFirst("(?is)\\s*```$", "")
                .trim();
    }
}
