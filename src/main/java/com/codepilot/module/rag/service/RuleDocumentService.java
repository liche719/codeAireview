package com.codepilot.module.rag.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.codepilot.module.rag.dto.CreateRuleDocumentRequest;
import com.codepilot.module.rag.dto.RuleDocumentDetailVO;
import com.codepilot.module.rag.dto.RuleDocumentVO;
import com.codepilot.module.rag.entity.RuleDocument;

import java.util.List;

public interface RuleDocumentService extends IService<RuleDocument> {

    Long createDocument(CreateRuleDocumentRequest request);

    List<RuleDocumentVO> listDocuments();

    RuleDocumentDetailVO getDocumentDetail(Long id);

    RuleDocument getRequiredDocument(Long id);
}
