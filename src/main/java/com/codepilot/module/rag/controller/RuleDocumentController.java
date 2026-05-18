package com.codepilot.module.rag.controller;

import com.codepilot.common.response.Result;
import com.codepilot.module.rag.dto.CreateRuleDocumentRequest;
import com.codepilot.module.rag.dto.CreateRuleDocumentResponse;
import com.codepilot.module.rag.dto.IndexRuleDocumentResponse;
import com.codepilot.module.rag.dto.RuleDocumentDetailVO;
import com.codepilot.module.rag.dto.RuleDocumentVO;
import com.codepilot.module.rag.dto.RuleSearchRequest;
import com.codepilot.module.rag.dto.RuleSearchResponse;
import com.codepilot.module.rag.service.RuleDocumentService;
import com.codepilot.module.rag.service.RuleIndexService;
import com.codepilot.module.rag.service.RuleSearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rules")
public class RuleDocumentController {

    private final RuleDocumentService ruleDocumentService;

    private final RuleIndexService ruleIndexService;

    private final RuleSearchService ruleSearchService;

    @PostMapping
    public Result<CreateRuleDocumentResponse> createRuleDocument(
            @Valid @RequestBody CreateRuleDocumentRequest request
    ) {
        return Result.success(new CreateRuleDocumentResponse(ruleDocumentService.createDocument(request)));
    }

    @GetMapping
    public Result<List<RuleDocumentVO>> listRuleDocuments() {
        return Result.success(ruleDocumentService.listDocuments());
    }

    @GetMapping("/{id}")
    public Result<RuleDocumentDetailVO> getRuleDocument(@PathVariable Long id) {
        return Result.success(ruleDocumentService.getDocumentDetail(id));
    }

    @PostMapping("/{id}/index")
    public Result<IndexRuleDocumentResponse> indexRuleDocument(@PathVariable Long id) {
        return Result.success(ruleIndexService.indexDocument(id));
    }

    @PostMapping("/search")
    public Result<RuleSearchResponse> searchRules(@Valid @RequestBody RuleSearchRequest request) {
        return Result.success(ruleSearchService.search(request));
    }
}
