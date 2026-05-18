package com.codepilot.module.agent.service;

import com.codepilot.module.agent.dto.AiReviewResult;

import java.util.List;

public interface AiReviewService {

    default AiReviewResult reviewFile(String filePath, String patch) {
        return reviewFile(null, filePath, patch);
    }

    default AiReviewResult reviewFile(Long taskId, String filePath, String patch) {
        return reviewFile(taskId, filePath, patch, List.of());
    }

    AiReviewResult reviewFile(Long taskId, String filePath, String patch, List<String> allChangedFiles);
}
