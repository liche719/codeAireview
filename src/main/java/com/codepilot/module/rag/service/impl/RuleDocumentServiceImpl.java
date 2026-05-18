package com.codepilot.module.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.rag.dto.CreateRuleDocumentRequest;
import com.codepilot.module.rag.dto.RuleDocumentDetailVO;
import com.codepilot.module.rag.dto.RuleDocumentVO;
import com.codepilot.module.rag.entity.RuleDocument;
import com.codepilot.module.rag.mapper.RuleDocumentMapper;
import com.codepilot.module.rag.service.RuleDocumentService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class RuleDocumentServiceImpl extends ServiceImpl<RuleDocumentMapper, RuleDocument> implements RuleDocumentService {

    @Override
    public Long createDocument(CreateRuleDocumentRequest request) {
        if (request == null) {
            throw new BusinessException("rule document request must not be null");
        }
        if (!StringUtils.hasText(request.getContent())) {
            throw new BusinessException("rule document content must not be blank");
        }

        RuleDocument document = new RuleDocument();
        document.setTitle(request.getTitle().trim());
        document.setType(trimToNull(request.getType()));
        document.setSource(trimToNull(request.getSource()));
        document.setContent(request.getContent().trim());
        document.setEnabled(true);
        document.setCreatedAt(LocalDateTime.now());
        save(document);
        return document.getId();
    }

    @Override
    public List<RuleDocumentVO> listDocuments() {
        return list(new LambdaQueryWrapper<RuleDocument>().orderByDesc(RuleDocument::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public RuleDocumentDetailVO getDocumentDetail(Long id) {
        return toDetailVO(getRequiredDocument(id));
    }

    @Override
    public RuleDocument getRequiredDocument(Long id) {
        if (id == null) {
            throw new BusinessException("rule document id must not be null");
        }
        RuleDocument document = getById(id);
        if (document == null) {
            throw new BusinessException("rule document not found, id=" + id);
        }
        return document;
    }

    private RuleDocumentVO toVO(RuleDocument document) {
        RuleDocumentVO vo = new RuleDocumentVO();
        vo.setId(document.getId());
        vo.setTitle(document.getTitle());
        vo.setType(document.getType());
        vo.setSource(document.getSource());
        vo.setEnabled(document.getEnabled());
        vo.setCreatedAt(document.getCreatedAt());
        return vo;
    }

    private RuleDocumentDetailVO toDetailVO(RuleDocument document) {
        RuleDocumentDetailVO vo = new RuleDocumentDetailVO();
        vo.setId(document.getId());
        vo.setTitle(document.getTitle());
        vo.setType(document.getType());
        vo.setSource(document.getSource());
        vo.setContent(document.getContent());
        vo.setEnabled(document.getEnabled());
        vo.setCreatedAt(document.getCreatedAt());
        return vo;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
