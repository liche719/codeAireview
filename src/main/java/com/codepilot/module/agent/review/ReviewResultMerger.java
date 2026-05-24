package com.codepilot.module.agent.review;

import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.agent.dto.AiReviewResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ReviewResultMerger {

    private final ReviewIssueDeduplicator reviewIssueDeduplicator;

    public AiReviewResult merge(AiReviewResult primaryResult, AiReviewResult deterministicResult) {
        List<AiReviewIssue> mergedIssues = new ArrayList<>();
        if (primaryResult != null && primaryResult.getIssues() != null) {
            mergedIssues.addAll(primaryResult.getIssues());
        }
        if (deterministicResult != null && deterministicResult.getIssues() != null) {
            mergedIssues.addAll(deterministicResult.getIssues());
        }

        AiReviewResult mergedResult = primaryResult == null ? AiReviewResult.empty() : primaryResult;
        mergedResult.setIssues(reviewIssueDeduplicator.dedupe(mergedIssues));
        if (!StringUtils.hasText(mergedResult.getSummary())
                && deterministicResult != null
                && StringUtils.hasText(deterministicResult.getSummary())) {
            mergedResult.setSummary(deterministicResult.getSummary());
        }
        return mergedResult;
    }
}
