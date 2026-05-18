package com.codepilot.module.agent.service;

import com.codepilot.module.agent.dto.AiReviewResult;

public interface AiReviewService {

    default AiReviewResult reviewFile(String filePath, String patch) {
        return reviewFile(null, filePath, patch);
    }

    AiReviewResult reviewFile(Long taskId, String filePath, String patch);
}

