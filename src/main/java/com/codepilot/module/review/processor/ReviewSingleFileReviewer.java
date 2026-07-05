package com.codepilot.module.review.processor;

import com.codepilot.module.agent.dto.AiReviewRequest;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.review.assembler.ReviewIssueAssembler;
import com.codepilot.module.review.context.ReviewContext;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ReviewSingleFileReviewer {

    private final AiReviewService aiReviewService;

    private final ReviewIssueAssembler reviewIssueAssembler;

    private final ReviewIssueLocationGuard reviewIssueLocationGuard;

    private final ReviewIssuePatchVerifier reviewIssuePatchVerifier;

    public List<ReviewIssue> review(Long taskId, ReviewFile reviewFile, ReviewContext reviewContext) {
        try {
            AiReviewResult aiReviewResult = aiReviewService.reviewFile(new AiReviewRequest(
                    taskId,
                    reviewFile.getFilePath(),
                    reviewFile.getPatch(),
                    reviewContext.toAiReviewContext()
            ));
            List<ReviewIssue> reviewIssues = reviewIssueAssembler.toReviewIssues(
                    taskId,
                    reviewFile.getFilePath(),
                    aiReviewResult
            );
            reviewIssues = reviewIssuePatchVerifier.keepVerified(
                    reviewFile.getFilePath(),
                    reviewFile.getPatch(),
                    reviewContext,
                    reviewIssues
            );
            return reviewIssueLocationGuard.keepOnlyCommentableChangedLines(
                    reviewFile.getFilePath(),
                    reviewFile.getPatch(),
                    reviewIssues
            );
        } catch (Exception exception) {
            throw new IllegalStateException("AI review failed for file " + reviewFile.getFilePath(), exception);
        }
    }
}
