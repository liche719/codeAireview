package com.codepilot.module.review.processor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.service.AiReviewService;
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
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ReviewTaskProcessor {

    private final GithubClient githubClient;

    private final ReviewFilePlanner reviewFilePlanner;

    private final ReviewFileService reviewFileService;

    private final ReviewIssueService reviewIssueService;

    private final AiReviewService aiReviewService;

    private final ReviewIssueAssembler reviewIssueAssembler;

    public ReviewTaskProcessingResult process(ReviewTask task) {
        var changedFiles = githubClient.listPullRequestFiles(
                task.getRepoOwner(),
                task.getRepoName(),
                task.getPrNumber()
        );
        List<ReviewFile> reviewFiles = reviewFilePlanner.plan(task.getId(), changedFiles);

        replaceReviewFiles(task.getId(), reviewFiles);
        clearReviewIssues(task.getId());
        List<ReviewIssue> reviewIssues = reviewFiles(task.getId(), reviewFiles);
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

    private List<ReviewIssue> reviewFiles(Long taskId, List<ReviewFile> reviewFiles) {
        List<String> allChangedFiles = reviewFiles.stream()
                .map(ReviewFile::getFilePath)
                .filter(StringUtils::hasText)
                .toList();
        List<ReviewIssue> reviewIssues = new ArrayList<>();
        for (ReviewFile reviewFile : reviewFiles) {
            if (!Boolean.TRUE.equals(reviewFile.getSkipped())) {
                reviewIssues.addAll(reviewFileWithAi(taskId, reviewFile, allChangedFiles));
            }
        }
        return reviewIssues;
    }

    private List<ReviewIssue> reviewFileWithAi(Long taskId, ReviewFile reviewFile, List<String> allChangedFiles) {
        try {
            AiReviewResult aiReviewResult = aiReviewService.reviewFile(
                    taskId,
                    reviewFile.getFilePath(),
                    reviewFile.getPatch(),
                    allChangedFiles
            );
            return reviewIssueAssembler.toReviewIssues(taskId, reviewFile.getFilePath(), aiReviewResult);
        } catch (Exception exception) {
            throw new IllegalStateException("AI review failed for file " + reviewFile.getFilePath(), exception);
        }
    }
}
