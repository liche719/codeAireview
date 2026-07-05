package com.codepilot.module.agent.service.impl;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.agent.config.RagProperties;
import com.codepilot.module.agent.dto.ReviewRuleContext;
import com.codepilot.module.agent.service.ReviewRagService;
import com.codepilot.module.rag.dto.RuleSearchRecord;
import com.codepilot.module.rag.service.RuleSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewRagServiceImpl implements ReviewRagService {

    private final RagProperties ragProperties;

    private final RuleSearchService ruleSearchService;

    private final ReviewRagQueryBuilder queryBuilder = new ReviewRagQueryBuilder();

    private final ReviewRagCacheKeyBuilder cacheKeyBuilder = new ReviewRagCacheKeyBuilder();

    private final ReviewRagCache cache = new ReviewRagCache();

    private final ReviewRagRuleSelector ruleSelector = new ReviewRagRuleSelector();

    @Override
    public List<ReviewRuleContext> retrieveRelevantRules(String filePath, String patch) {
        if (!ragProperties.isEnabled()) {
            log.info("RAG skipped because codepilot.rag.enabled=false, filePath={}", filePath);
            return List.of();
        }
        if (!StringUtils.hasText(patch)) {
            return List.of();
        }
        if (patch.trim().length() < Math.max(0, ragProperties.getMinContentLength())) {
            log.info("RAG skipped because patch is too short, filePath={}, patchLength={}", filePath, patch.length());
            return List.of();
        }

        try {
            String query = buildRuleSearchQuery(filePath, patch);
            if (!StringUtils.hasText(query)) {
                return List.of();
            }

            int topK = Math.max(1, ragProperties.getTopK());
            int maxContextChars = Math.max(0, ragProperties.getMaxContextChars());
            List<String> ruleTypes = inferRuleTypes(filePath, patch);
            String cacheKey = cacheKeyBuilder.build(query, topK, maxContextChars, ruleTypes);
            List<ReviewRuleContext> cachedContexts = cache.get(cacheKey, ragProperties);
            if (cachedContexts != null) {
                log.info("RAG cache hit, filePath={}, ruleTypes={}, ruleCount={}, contextChars={}",
                        filePath, ruleTypes, cachedContexts.size(), ruleSelector.totalContentLength(cachedContexts));
                return cachedContexts;
            }

            List<RuleSearchRecord> records = ruleSelector.searchByRuleTypes(
                    ruleSearchService,
                    query,
                    topK,
                    ruleTypes
            );
            List<ReviewRuleContext> contexts = ruleSelector.limitContext(records, maxContextChars);
            cache.put(cacheKey, contexts, ragProperties);
            log.info("RAG retrieved rules, filePath={}, ruleTypes={}, ruleCount={}, contextChars={}",
                    filePath, ruleTypes, contexts.size(), ruleSelector.totalContentLength(contexts));
            return contexts;
        } catch (Exception exception) {
            log.warn("RAG retrieval failed, fallback to plain ai review, filePath={}, message={}",
                    filePath, SensitiveDataSanitizer.redact(exception.getMessage()));
            return List.of();
        }
    }

    public String buildRuleSearchQuery(String filePath, String patch) {
        return queryBuilder.buildRuleSearchQuery(filePath, patch);
    }

    public List<String> inferRuleTypes(String filePath, String patch) {
        return queryBuilder.inferRuleTypes(filePath, patch);
    }
}
