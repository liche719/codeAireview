package com.codepilot.infrastructure.embedding;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.rag.entity.RuleDocument;
import com.codepilot.module.rag.mapper.RuleChunkMapper;
import com.codepilot.module.rag.service.RuleDocumentService;
import com.codepilot.module.rag.service.RuleIndexService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingDimensionBootstrap implements ApplicationRunner {

    private static final String OPENAI_COMPATIBLE_PROVIDER = "openai-compatible";

    private static final String PROBE_TEXT = "CodePilot embedding dimension probe";

    private final EmbeddingProperties embeddingProperties;

    private final EmbeddingModel embeddingModel;

    private final RuleChunkMapper ruleChunkMapper;

    private final RuleDocumentService ruleDocumentService;

    private final RuleIndexService ruleIndexService;

    @Override
    public void run(ApplicationArguments args) {
        if (!shouldBootstrap()) {
            log.info("Skip embedding dimension bootstrap because embedding is disabled or unavailable");
            return;
        }

        int resolvedDimension = detectEmbeddingDimension();
        Integer configuredDimension = embeddingProperties.getDimension() > 0 ? embeddingProperties.getDimension() : null;
        embeddingProperties.setDimension(resolvedDimension);
        if (configuredDimension == null) {
            log.info("Embedding dimension auto-detected at startup, dimension={}", resolvedDimension);
        } else if (!configuredDimension.equals(resolvedDimension)) {
            log.warn("Embedding dimension resolved from model differs from configured value, configured={}, resolved={}",
                    configuredDimension, resolvedDimension);
        } else {
            log.info("Embedding dimension confirmed at startup, dimension={}", resolvedDimension);
        }

        synchronizeRuleChunkSchema(resolvedDimension);
    }

    private boolean shouldBootstrap() {
        return embeddingProperties.isEnabled()
                && StringUtils.hasText(embeddingProperties.getApiKey())
                && OPENAI_COMPATIBLE_PROVIDER.equalsIgnoreCase(
                StringUtils.trimWhitespace(embeddingProperties.getProvider() == null ? "" : embeddingProperties.getProvider())
        );
    }

    private int detectEmbeddingDimension() {
        try {
            Response<Embedding> response = embeddingModel.embed(PROBE_TEXT);
            Embedding embedding = response == null ? null : response.content();
            if (embedding == null || embedding.dimension() <= 0) {
                throw new BusinessException("embedding model returned empty dimension");
            }
            return embedding.dimension();
        } catch (Exception exception) {
            throw new BusinessException("failed to detect embedding dimension: "
                    + SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }

    private void synchronizeRuleChunkSchema(int resolvedDimension) {
        String columnType = ruleChunkMapper.selectEmbeddingColumnType();
        boolean needsColumnMigration = columnType == null || !"vector".equalsIgnoreCase(columnType.trim());
        if (needsColumnMigration) {
            ruleChunkMapper.alterEmbeddingColumnToFlexibleVector();
            log.info("Rule chunk embedding column migrated to flexible vector type");
        }

        List<Integer> existingDimensions = ruleChunkMapper.selectEmbeddingDimensions();
        if (existingDimensions.isEmpty()) {
            return;
        }

        boolean dimensionMismatch = existingDimensions.stream().anyMatch(dimension -> dimension == null || dimension != resolvedDimension);
        if (!dimensionMismatch) {
            return;
        }

        int deletedChunks = ruleChunkMapper.deleteAll();
        log.warn("Rule chunk embeddings are out of date and will be rebuilt, existingDimensions={}, resolvedDimension={}, deletedChunks={}",
                existingDimensions, resolvedDimension, deletedChunks);

        List<RuleDocument> documents = ruleDocumentService.list().stream()
                .filter(document -> document != null && Boolean.TRUE.equals(document.getEnabled()))
                .filter(document -> StringUtils.hasText(document.getContent()))
                .toList();
        for (RuleDocument document : documents) {
            ruleIndexService.indexDocument(document.getId());
        }
        log.info("Rule chunk embeddings rebuilt, documentCount={}, resolvedDimension={}", documents.size(), resolvedDimension);
    }
}
