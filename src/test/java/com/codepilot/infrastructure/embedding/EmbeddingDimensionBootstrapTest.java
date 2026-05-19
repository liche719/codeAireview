package com.codepilot.infrastructure.embedding;

import com.codepilot.module.rag.dto.IndexRuleDocumentResponse;
import com.codepilot.module.rag.entity.RuleDocument;
import com.codepilot.module.rag.mapper.RuleChunkMapper;
import com.codepilot.module.rag.service.RuleDocumentService;
import com.codepilot.module.rag.service.RuleIndexService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingDimensionBootstrapTest {

    @Test
    void shouldSkipBootstrapWhenApiKeyMissing() throws Exception {
        TestContext context = new TestContext(false, "");

        context.bootstrap.run(new DefaultApplicationArguments());

        verifyNoInteractions(context.embeddingModel, context.ruleChunkMapper, context.ruleDocumentService, context.ruleIndexService);
        assertThat(context.embeddingProperties.getDimension()).isZero();
    }

    @Test
    void shouldAutoDetectDimensionAndRebuildRuleChunksWhenStoredDimensionsDiffer() throws Exception {
        TestContext context = new TestContext(true, "token");
        when(context.embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{0.1F, 0.2F, 0.3F})));
        when(context.ruleChunkMapper.selectEmbeddingColumnType()).thenReturn("vector(1536)");
        when(context.ruleChunkMapper.selectEmbeddingDimensions()).thenReturn(List.of(1536));
        when(context.ruleChunkMapper.deleteAll()).thenReturn(2);
        when(context.ruleIndexService.indexDocument(1L)).thenReturn(new IndexRuleDocumentResponse(1L, 1));
        when(context.ruleDocumentService.list()).thenReturn(List.of(enabledRuleDocument(1L)));

        context.bootstrap.run(new DefaultApplicationArguments());

        assertThat(context.embeddingProperties.getDimension()).isEqualTo(3);
        verify(context.ruleChunkMapper).alterEmbeddingColumnToFlexibleVector();
        verify(context.ruleChunkMapper).deleteAll();
        verify(context.ruleIndexService).indexDocument(1L);
    }

    @Test
    void shouldNotRebuildRuleChunksWhenStoredDimensionsMatchDetectedDimension() throws Exception {
        TestContext context = new TestContext(true, "token");
        when(context.embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{0.1F, 0.2F, 0.3F})));
        when(context.ruleChunkMapper.selectEmbeddingColumnType()).thenReturn("vector");
        when(context.ruleChunkMapper.selectEmbeddingDimensions()).thenReturn(List.of(3));

        context.bootstrap.run(new DefaultApplicationArguments());

        assertThat(context.embeddingProperties.getDimension()).isEqualTo(3);
        verify(context.ruleChunkMapper, never()).alterEmbeddingColumnToFlexibleVector();
        verify(context.ruleChunkMapper, never()).deleteAll();
        verify(context.ruleDocumentService, never()).list();
        verify(context.ruleIndexService, never()).indexDocument(1L);
    }

    private RuleDocument enabledRuleDocument(Long id) {
        RuleDocument document = new RuleDocument();
        document.setId(id);
        document.setEnabled(true);
        document.setContent("rule content");
        return document;
    }

    private static class TestContext {

        private final EmbeddingProperties embeddingProperties = new EmbeddingProperties();

        private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

        private final RuleChunkMapper ruleChunkMapper = mock(RuleChunkMapper.class);

        private final RuleDocumentService ruleDocumentService = mock(RuleDocumentService.class);

        private final RuleIndexService ruleIndexService = mock(RuleIndexService.class);

        private final EmbeddingDimensionBootstrap bootstrap;

        private TestContext(boolean enabled, String apiKey) {
            embeddingProperties.setEnabled(enabled);
            embeddingProperties.setProvider("openai-compatible");
            embeddingProperties.setApiKey(apiKey);
            bootstrap = new EmbeddingDimensionBootstrap(
                    embeddingProperties,
                    embeddingModel,
                    ruleChunkMapper,
                    ruleDocumentService,
                    ruleIndexService
            );
        }
    }
}
