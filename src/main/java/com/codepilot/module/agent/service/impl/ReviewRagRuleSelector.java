package com.codepilot.module.agent.service.impl;

import com.codepilot.module.agent.dto.ReviewRuleContext;
import com.codepilot.module.rag.dto.RuleSearchRecord;
import com.codepilot.module.rag.dto.RuleSearchResponse;
import com.codepilot.module.rag.service.RuleSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
final class ReviewRagRuleSelector {

    List<RuleSearchRecord> searchByRuleTypes(
            RuleSearchService ruleSearchService,
            String query,
            int topK,
            List<String> ruleTypes
    ) {
        RuleSearchResponse response = ruleSearchService.searchByTypes(query, topK, ruleTypes);
        List<RuleSearchRecord> mergedRecords = mergeTopK(response == null ? null : response.getRecords(), topK);
        if (!mergedRecords.isEmpty() || ruleTypes == null || ruleTypes.isEmpty()) {
            return mergedRecords;
        }

        log.info("RAG typed search returned no rule, fallback to unfiltered search, ruleTypes={}", ruleTypes);
        RuleSearchResponse fallbackResponse = ruleSearchService.searchByTypes(query, topK, List.of());
        return mergeTopK(fallbackResponse == null ? null : fallbackResponse.getRecords(), topK);
    }

    List<ReviewRuleContext> limitContext(List<RuleSearchRecord> records, int maxContextChars) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        int usedChars = 0;
        List<ReviewRuleContext> contexts = new ArrayList<>();
        for (RuleSearchRecord record : records) {
            if (record == null || !StringUtils.hasText(record.getContent())) {
                continue;
            }
            int remaining = maxContextChars - usedChars;
            if (remaining <= 0) {
                break;
            }

            ReviewRuleContext context = new ReviewRuleContext();
            context.setChunkId(record.getChunkId());
            context.setDocumentId(record.getDocumentId());
            context.setType(record.getType());
            context.setDistance(record.getDistance());
            context.setContent(truncate(record.getContent().trim(), remaining));
            usedChars += context.getContent().length();
            contexts.add(context);
        }
        return contexts;
    }

    int totalContentLength(List<ReviewRuleContext> contexts) {
        return contexts.stream()
                .map(ReviewRuleContext::getContent)
                .filter(StringUtils::hasText)
                .mapToInt(String::length)
                .sum();
    }

    private List<RuleSearchRecord> mergeTopK(List<RuleSearchRecord> records, int topK) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        Map<String, RuleSearchRecord> deduplicatedRecords = new LinkedHashMap<>();
        for (RuleSearchRecord record : records) {
            if (record == null || !StringUtils.hasText(record.getContent())) {
                continue;
            }
            String key = dedupeKey(record);
            deduplicatedRecords.merge(key, record, this::nearestRecord);
        }

        return deduplicatedRecords.values().stream()
                .sorted(Comparator.comparing(
                        RuleSearchRecord::getDistance,
                        Comparator.nullsLast(Double::compareTo)
                ))
                .limit(topK)
                .toList();
    }

    private String dedupeKey(RuleSearchRecord record) {
        if (record.getChunkId() != null) {
            return "chunk:" + record.getChunkId();
        }
        return "content:" + record.getDocumentId() + ":" + record.getContent();
    }

    private RuleSearchRecord nearestRecord(RuleSearchRecord left, RuleSearchRecord right) {
        Double leftDistance = left.getDistance();
        Double rightDistance = right.getDistance();
        if (leftDistance == null) {
            return right;
        }
        if (rightDistance == null) {
            return left;
        }
        return leftDistance <= rightDistance ? left : right;
    }

    private String truncate(String content, int maxLength) {
        if (content == null || content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, Math.max(0, maxLength));
    }
}
