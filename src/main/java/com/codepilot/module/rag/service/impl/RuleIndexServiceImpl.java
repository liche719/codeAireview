package com.codepilot.module.rag.service.impl;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.infrastructure.embedding.EmbeddingService;
import com.codepilot.module.rag.dto.IndexRuleDocumentResponse;
import com.codepilot.module.rag.entity.RuleChunk;
import com.codepilot.module.rag.entity.RuleDocument;
import com.codepilot.module.rag.mapper.RuleChunkMapper;
import com.codepilot.module.rag.service.RuleDocumentService;
import com.codepilot.module.rag.service.RuleIndexService;
import com.codepilot.module.rag.splitter.RuleTextSplitter;
import com.codepilot.module.rag.util.PgVectorUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleIndexServiceImpl implements RuleIndexService {

    private final RuleDocumentService ruleDocumentService;

    private final RuleChunkMapper ruleChunkMapper;

    private final RuleTextSplitter ruleTextSplitter;

    private final EmbeddingService embeddingService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IndexRuleDocumentResponse indexDocument(Long documentId) {
        RuleDocument document = ruleDocumentService.getRequiredDocument(documentId);
        if (!StringUtils.hasText(document.getContent())) {
            throw new BusinessException("rule document content must not be blank");
        }

        List<String> chunks = ruleTextSplitter.split(document.getContent());
        if (chunks.isEmpty()) {
            throw new BusinessException("rule document split result is empty");
        }

        ruleChunkMapper.deleteByDocumentId(documentId);

        for (int index = 0; index < chunks.size(); index++) {
            String chunkContent = chunks.get(index);
            List<Float> embedding = embeddingService.embed(chunkContent);

            RuleChunk chunk = new RuleChunk();
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(index);
            chunk.setContent(chunkContent);
            chunk.setEmbedding(PgVectorUtils.toVectorString(embedding));
            chunk.setType(document.getType());
            chunk.setCreatedAt(LocalDateTime.now());
            ruleChunkMapper.insertVector(chunk);
        }

        log.info("Rule document indexed, documentId={}, chunkCount={}", documentId, chunks.size());
        return new IndexRuleDocumentResponse(documentId, chunks.size());
    }
}
