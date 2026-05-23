package com.codepilot.module.review.processor;

import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.dto.AiReviewRequest;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.review.assembler.ReviewIssueAssembler;
import com.codepilot.module.review.context.ReviewContext;
import com.codepilot.module.review.context.ReviewContextBuilder;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ReviewFileReviewer {

    private final AiReviewService aiReviewService;

    private final ReviewIssueAssembler reviewIssueAssembler;

    private final ReviewContextBuilder reviewContextBuilder;

    public List<ReviewIssue> review(Long taskId, List<ReviewFile> reviewFiles) {
        if (reviewFiles == null || reviewFiles.isEmpty()) {
            return List.of();
        }
        ReviewContext reviewContext = reviewContextBuilder.build(reviewFiles);
        List<ReviewIssue> reviewIssues = new ArrayList<>();
        for (ReviewFile reviewFile : reviewFiles) {
            if (!Boolean.TRUE.equals(reviewFile.getSkipped())) {
                reviewIssues.addAll(reviewFileWithAi(taskId, reviewFile, reviewContext));
            }
        }
        return reviewIssues;
    }

    private List<ReviewIssue> reviewFileWithAi(Long taskId, ReviewFile reviewFile, ReviewContext reviewContext) {
        try {
            AiReviewResult aiReviewResult = aiReviewService.reviewFile(new AiReviewRequest(
                    taskId,
                    reviewFile.getFilePath(),
                    reviewFile.getPatch(),
                    reviewContext.toAiReviewContext()
            ));
            return reviewIssueAssembler.toReviewIssues(taskId, reviewFile.getFilePath(), aiReviewResult);
        } catch (Exception exception) {
            throw new IllegalStateException("AI review failed for file " + reviewFile.getFilePath(), exception);
        }
    }
}
