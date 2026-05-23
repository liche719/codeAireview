package com.codepilot.module.review.processor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.review.assembler.ReviewIssueAssembler;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.planner.ReviewFilePlanner;
import com.codepilot.module.review.service.ReviewFileService;
import com.codepilot.module.review.service.ReviewIssueService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ReviewTaskProcessor {

    private final GithubClient githubClient;

    private final ReviewFilePlanner reviewFilePlanner;

    private final ReviewFileService reviewFileService;

    private final ReviewIssueService reviewIssueService;

    private final ReviewIssueAssembler reviewIssueAssembler;

    private final ReviewFileReviewer reviewFileReviewer;

    public ReviewTaskProcessingResult process(ReviewTask task) {
        var changedFiles = githubClient.listPullRequestFiles(
                task.getRepoOwner(),
                task.getRepoName(),
                task.getPrNumber()
        );
        List<ReviewFile> reviewFiles = reviewFilePlanner.plan(task.getId(), changedFiles);

        replaceReviewFiles(task.getId(), reviewFiles);
        clearReviewIssues(task.getId());
        List<ReviewIssue> reviewIssues = reviewFileReviewer.review(task.getId(), reviewFiles);
        saveReviewIssues(reviewIssues);

        return new ReviewTaskProcessingResult(
                changedFiles.size(),
                reviewIssues.size(),
                reviewIssueAssembler.calculateRiskLevel(reviewIssues)
        );
    }

    private void replaceReviewFiles(Long taskId, List<ReviewFile> reviewFiles) {
        reviewFileService.remove(new LambdaQueryWrapper<ReviewFile>()
                .eq(ReviewFile::getTaskId, taskId));
        reviewFileService.saveBatch(reviewFiles);
    }

    private void clearReviewIssues(Long taskId) {
        reviewIssueService.remove(new LambdaQueryWrapper<ReviewIssue>()
                .eq(ReviewIssue::getTaskId, taskId));
    }

    private void saveReviewIssues(List<ReviewIssue> reviewIssues) {
        if (!reviewIssues.isEmpty()) {
            reviewIssueService.saveBatch(reviewIssues);
        }
    }

}
