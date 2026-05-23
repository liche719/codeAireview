package com.codepilot.module.agent.service;

import com.codepilot.module.agent.dto.AiReviewRequest;
import com.codepilot.module.agent.dto.AiReviewResult;

import java.util.List;

public interface AiReviewService {

    default AiReviewResult reviewFile(String filePath, String patch) {
        return reviewFile(new AiReviewRequest(null, filePath, patch, List.of()));
    }

    default AiReviewResult reviewFile(Long taskId, String filePath, String patch) {
        return reviewFile(new AiReviewRequest(taskId, filePath, patch, List.of()));
    }

    default AiReviewResult reviewFile(Long taskId, String filePath, String patch, List<String> allChangedFiles) {
        return reviewFile(new AiReviewRequest(taskId, filePath, patch, allChangedFiles));
    }

    AiReviewResult reviewFile(AiReviewRequest request);
}
