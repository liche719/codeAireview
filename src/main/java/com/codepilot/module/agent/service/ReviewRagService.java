package com.codepilot.module.agent.service;

import com.codepilot.module.agent.dto.ReviewRuleContext;

import java.util.List;

public interface ReviewRagService {

    List<ReviewRuleContext> retrieveRelevantRules(String filePath, String patch);
}
