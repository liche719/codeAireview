package com.codepilot.module.rag.service;

import com.codepilot.module.rag.dto.RuleSearchRequest;
import com.codepilot.module.rag.dto.RuleSearchResponse;

public interface RuleSearchService {

    RuleSearchResponse search(RuleSearchRequest request);
}
