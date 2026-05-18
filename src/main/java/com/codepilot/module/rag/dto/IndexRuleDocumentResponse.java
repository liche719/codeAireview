package com.codepilot.module.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndexRuleDocumentResponse {

    private Long documentId;

    private Integer chunkCount;
}
