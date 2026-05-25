package com.codepilot.module.tool.rule;

import com.codepilot.module.tool.dto.ToolCheckRequest;
import com.codepilot.module.tool.dto.ToolCheckResult;

import java.util.List;

public interface DeterministicReviewRule {

    String id();

    default int order() {
        return 100;
    }

    default boolean supports(ToolCheckRequest request) {
        return true;
    }

    List<ToolCheckResult> check(ToolCheckRequest request);
}
