package com.codepilot.module.agent.service.impl;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.agent.dto.AiReviewContext;
import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.agent.dto.AiReviewRequest;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.review.DeterministicReviewToolRunner;
import com.codepilot.module.agent.review.ReviewIssueDeduplicator;
import com.codepilot.module.agent.review.ReviewLlmReviewer;
import com.codepilot.module.agent.service.AiReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiReviewServiceImpl implements AiReviewService {

    private static final int SKIPPED_FILE_CONTEXT_LIMIT = 20;

    private final LlmProperties llmProperties;

    private final DeterministicReviewToolRunner deterministicReviewToolRunner;

    private final ReviewLlmReviewer reviewLlmReviewer;

    private final ReviewIssueDeduplicator reviewIssueDeduplicator;

    @Override
    public AiReviewResult reviewFile(AiReviewRequest request) {
        String filePath = request == null ? null : request.filePath();
        String patch = request == null ? null : request.patch();
        AiReviewContext context = request == null ? AiReviewContext.empty() : request.context();
        if (!StringUtils.hasText(patch)) {
            log.info("Skip ai review because patch is empty, filePath={}", filePath);
            return AiReviewResult.empty();
        }

        String allChangedFilesText = buildReviewContextText(context);
        AiReviewResult deterministicResult = deterministicReviewToolRunner.run(filePath, patch, allChangedFilesText);
        if (!llmProperties.isEnabled()) {
            log.info("Skip llm review because llm is disabled, filePath={}, deterministicIssueCount={}",
                    filePath, issueCount(deterministicResult));
            return deterministicResult;
        }
        if (!StringUtils.hasText(llmProperties.getApiKey())) {
            log.info("Skip llm review because llm api key is missing, filePath={}, deterministicIssueCount={}",
                    filePath, issueCount(deterministicResult));
            return deterministicResult;
        }

        AiReviewResult llmResult = reviewLlmReviewer
                .review(request, allChangedFilesText, issueCount(deterministicResult))
                .orElse(null);
        if (llmResult == null) {
            return deterministicResult;
        }
        return mergeResults(llmResult, deterministicResult);
    }

    private String buildReviewContextText(AiReviewContext context) {
        AiReviewContext safeContext = context == null ? AiReviewContext.empty() : context;
        List<String> allChangedFiles = safeContext.allChangedFiles();
        if (allChangedFiles.isEmpty()) {
            return "No changed file list was provided.";
        }

        StringBuilder builder = new StringBuilder()
                .append("Changed files (")
                .append(safeContext.totalFileCount())
                .append(" total, ")
                .append(safeContext.reviewableFileCount())
                .append(" reviewable, ")
                .append(safeContext.skippedFileCount())
                .append(" skipped, +")
                .append(safeContext.totalAdditions())
                .append(" / -")
                .append(safeContext.totalDeletions())
                .append(", patchChars=")
                .append(safeContext.totalPatchChars())
                .append("):\n");
        builder.append(String.join("\n", allChangedFiles));

        List<AiReviewContext.SkippedFile> skippedFiles = safeContext.skippedFiles();
        if (!skippedFiles.isEmpty()) {
            builder.append("\n\nSkipped files:\n");
            int limit = Math.min(skippedFiles.size(), SKIPPED_FILE_CONTEXT_LIMIT);
            for (int index = 0; index < limit; index++) {
                AiReviewContext.SkippedFile skippedFile = skippedFiles.get(index);
                builder.append("- ")
                        .append(skippedFile.filePath())
                        .append(": ")
                        .append(StringUtils.hasText(skippedFile.reason()) ? skippedFile.reason() : "skipped")
                        .append('\n');
            }
            if (skippedFiles.size() > SKIPPED_FILE_CONTEXT_LIMIT) {
                builder.append("- ")
                        .append(skippedFiles.size() - SKIPPED_FILE_CONTEXT_LIMIT)
                        .append(" more skipped files omitted\n");
            }
        }
        return builder.toString();
    }

    private AiReviewResult mergeResults(AiReviewResult llmResult, AiReviewResult deterministicResult) {
        List<AiReviewIssue> mergedIssues = new ArrayList<>();
        if (llmResult != null && llmResult.getIssues() != null) {
            mergedIssues.addAll(llmResult.getIssues());
        }
        if (deterministicResult != null && deterministicResult.getIssues() != null) {
            mergedIssues.addAll(deterministicResult.getIssues());
        }
        AiReviewResult mergedResult = llmResult == null ? AiReviewResult.empty() : llmResult;
        mergedResult.setIssues(reviewIssueDeduplicator.dedupe(mergedIssues));
        if (!StringUtils.hasText(mergedResult.getSummary())
                && deterministicResult != null
                && StringUtils.hasText(deterministicResult.getSummary())) {
            mergedResult.setSummary(deterministicResult.getSummary());
        }
        return mergedResult;
    }

    private int issueCount(AiReviewResult result) {
        return result == null || result.getIssues() == null ? 0 : result.getIssues().size();
    }
}
