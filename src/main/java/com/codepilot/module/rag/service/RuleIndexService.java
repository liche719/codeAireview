package com.codepilot.module.rag.service;

import com.codepilot.module.rag.dto.IndexRuleDocumentResponse;

public interface RuleIndexService {

    IndexRuleDocumentResponse indexDocument(Long documentId);
}
