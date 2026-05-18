package com.codepilot.module.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class RuleTextSplitter {

    private static final int MAX_CHUNK_LENGTH = 500;

    private static final int OVERLAP_LENGTH = 50;

    private final DocumentSplitter documentSplitter = DocumentSplitters.recursive(MAX_CHUNK_LENGTH, OVERLAP_LENGTH);

    public List<String> split(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }

        return documentSplitter.split(Document.from(content))
                .stream()
                .map(TextSegment::text)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
