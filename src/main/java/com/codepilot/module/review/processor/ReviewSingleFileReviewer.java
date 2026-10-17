package com.codepilot.module.review.processor;

import com.codepilot.module.agent.dto.AiReviewRequest;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.review.assembler.ReviewIssueAssembler;
import com.codepilot.module.review.context.ReviewContext;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class ReviewSingleFileReviewer {

    private final AiReviewService aiReviewService;

    private final ReviewIssueAssembler reviewIssueAssembler;

    private final ReviewIssueLocationGuard reviewIssueLocationGuard;

    private final ReviewIssuePatchVerifier reviewIssuePatchVerifier;

    /**
     * 自省闸门（R18）。可为 null：手工 new 出来的测试不需要它，此时直接跳过。
     */
    private final FindingReflectionService findingReflectionService;

    /**
     * 不带自省的便捷构造：多数既有测试走这条。
     */
    public ReviewSingleFileReviewer(
            AiReviewService aiReviewService,
            ReviewIssueAssembler reviewIssueAssembler,
            ReviewIssueLocationGuard reviewIssueLocationGuard,
            ReviewIssuePatchVerifier reviewIssuePatchVerifier
    ) {
        this(aiReviewService, reviewIssueAssembler, reviewIssueLocationGuard, reviewIssuePatchVerifier, null);
    }

    public List<ReviewIssue> review(Long taskId, ReviewFile reviewFile, ReviewContext reviewContext) {
        try {
            AiReviewResult aiReviewResult = aiReviewService.reviewFile(new AiReviewRequest(
                    taskId,
                    reviewFile.getFilePath(),
                    reviewFile.getPatch(),
                    reviewContext.toAiReviewContext(),
                    reviewContext.dismissedFindings(),
                    reviewContext.pathInstructions()
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
            reviewIssues = reviewIssueLocationGuard.keepOnlyCommentableChangedLines(
                    reviewFile.getFilePath(),
                    reviewFile.getPatch(),
                    reviewIssues
            );
            // R18：自省闸门放在两道质量门<b>之后</b>——先去掉确定不成立的，
            // 再花模型调用复核剩下的，避免为大概率会被丢弃的 finding 付两遍钱。
            if (findingReflectionService == null) {
                return reviewIssues;
            }
            return findingReflectionService.reflect(
                    reviewFile.getFilePath(), reviewFile.getPatch(), reviewIssues);
        } catch (Exception exception) {
            throw new IllegalStateException("AI review failed for file " + reviewFile.getFilePath(), exception);
        }
    }
}
