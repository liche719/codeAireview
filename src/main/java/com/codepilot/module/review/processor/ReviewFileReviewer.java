package com.codepilot.module.review.processor;

import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.review.assembler.ReviewIssueAssembler;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.context.ReviewContext;
import com.codepilot.module.review.context.ReviewContextBuilder;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ReviewFileReviewer {

    private final ReviewContextBuilder reviewContextBuilder;

    private final ReviewFilePrioritizer reviewFilePrioritizer;

    private final ReviewFileReviewExecutor reviewFileReviewExecutor;

    private final ReviewFindingRanker reviewFindingRanker;

    private final ReviewFileFailureIssueFactory failureIssueFactory;

    @Autowired
    public ReviewFileReviewer(
            ReviewContextBuilder reviewContextBuilder,
            ReviewFilePrioritizer reviewFilePrioritizer,
            ReviewFileReviewExecutor reviewFileReviewExecutor,
            ReviewFindingRanker reviewFindingRanker,
            ReviewFileFailureIssueFactory failureIssueFactory
    ) {
        this.reviewContextBuilder = reviewContextBuilder;
        this.reviewFilePrioritizer = reviewFilePrioritizer;
        this.reviewFileReviewExecutor = reviewFileReviewExecutor;
        this.reviewFindingRanker = reviewFindingRanker;
        this.failureIssueFactory = failureIssueFactory;
    }

    public ReviewFileReviewer(
            AiReviewService aiReviewService,
            ReviewIssueAssembler reviewIssueAssembler,
            ReviewIssueLocationGuard reviewIssueLocationGuard,
            ReviewIssuePatchVerifier reviewIssuePatchVerifier,
            ReviewContextBuilder reviewContextBuilder,
            ReviewFindingRanker reviewFindingRanker,
            ReviewProperties reviewProperties
    ) {
        this(
                reviewContextBuilder,
                new ReviewFilePrioritizer(),
                new ReviewFileReviewExecutor(
                        new ReviewSingleFileReviewer(
                                aiReviewService,
                                reviewIssueAssembler,
                                reviewIssueLocationGuard,
                                reviewIssuePatchVerifier
                        ),
                        new ReviewFileFailureIssueFactory(),
                        reviewProperties
                ),
                reviewFindingRanker,
                new ReviewFileFailureIssueFactory()
        );
    }

    public List<ReviewIssue> review(Long taskId, List<ReviewFile> reviewFiles) {
        return review(task(taskId), reviewFiles);
    }

    public List<ReviewIssue> review(ReviewTask task, List<ReviewFile> reviewFiles) {
        if (reviewFiles == null || reviewFiles.isEmpty()) {
            return List.of();
        }
        Long taskId = task == null ? null : task.getId();
        ReviewContext reviewContext = reviewContextBuilder.build(task, reviewFiles);
        List<ReviewFile> reviewableFiles = reviewFiles.stream()
                .filter(reviewFile -> !Boolean.TRUE.equals(reviewFile.getSkipped()))
                .toList();
        List<ReviewFile> plannedReviewFiles = reviewFilePrioritizer.prioritize(reviewableFiles, reviewContext);
        List<ReviewIssue> reviewIssues = new ArrayList<>();
        int failedFileCount = 0;
        Exception firstFailure = null;
        for (ReviewFileOutcome outcome : reviewFileReviewExecutor.reviewFiles(taskId, plannedReviewFiles, reviewContext)) {
            if (outcome.failure() != null) {
                failedFileCount++;
                if (firstFailure == null) {
                    firstFailure = outcome.failure();
                }
            }
            reviewIssues.addAll(outcome.issues());
        }
        // 2026-10-29：PR 里已经补了测试时，丢弃模型报的 TEST_MISSING。
        // 真实模型评测里这条误报在提示词写了两遍之后仍然出现——系统明明知道变更文件里有测试，
        // 就不该继续指望模型自己推断。
        reviewIssues = ReviewTestCoverageGuard.suppressUnfoundedTestMissing(reviewIssues, reviewableFiles);
        reviewIssues = reviewFindingRanker.rank(reviewIssues);
        if (!reviewableFiles.isEmpty() && failedFileCount == reviewableFiles.size()) {
            throw new IllegalStateException("AI review failed for all reviewable files, failedCount=" + failedFileCount
                    + ", firstError=" + failureIssueFactory.failureMessage(firstFailure),
                    firstFailure);
        }
        return reviewIssues;
    }

    private ReviewTask task(Long taskId) {
        ReviewTask task = new ReviewTask();
        task.setId(taskId);
        return task;
    }

}
