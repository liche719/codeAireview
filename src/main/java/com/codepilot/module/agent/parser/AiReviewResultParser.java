package com.codepilot.module.agent.parser;

import com.codepilot.module.agent.dto.AiReviewResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiReviewResultParser {

    private static final Pattern JSON_FENCE_PATTERN = Pattern.compile("^```(?:json)?\\s*(.*?)\\s*```$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    public AiReviewResult parse(String content) {
        if (!StringUtils.hasText(content)) {
            return AiReviewResult.empty();
        }

        String normalizedContent = normalize(content);
        try {
            AiReviewResult result = objectMapper.readValue(normalizedContent, AiReviewResult.class);
            if (result.getIssues() == null) {
                result.setIssues(new java.util.ArrayList<>());
            }
            return result;
        } catch (Exception exception) {
            log.warn("Failed to parse ai review result", exception);
            return AiReviewResult.empty();
        }
    }

    private String normalize(String content) {
        String trimmed = content.trim();
        Matcher matcher = JSON_FENCE_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            return matcher.group(1).trim();
        }
        return trimmed;
    }
}

