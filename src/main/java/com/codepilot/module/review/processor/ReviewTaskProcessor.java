package com.codepilot.module.review.processor;

import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.review.assembler.ReviewIssueAssembler;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.planner.ReviewFilePlanner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ReviewTaskProcessor {

    private final GithubClient githubClient;

    private final ReviewFilePlanner reviewFilePlanner;

    private final ReviewIssueAssembler reviewIssueAssembler;

    private final ReviewFileReviewer reviewFileReviewer;

    public ReviewTaskProcessingResult process(ReviewTask task) {
        var changedFiles = githubClient.listPullRequestFiles(
                task.getRepoOwner(),
                task.getRepoName(),
                task.getPrNumber()
        );
        List<ReviewFile> reviewFiles = reviewFilePlanner.plan(task.getId(), changedFiles);

        List<ReviewIssue> reviewIssues = reviewFileReviewer.review(task, reviewFiles);

        return new ReviewTaskProcessingResult(
                changedFiles.size(),
                reviewIssues.size(),
                reviewIssueAssembler.calculateRiskLevel(reviewIssues),
                reviewFiles,
                reviewIssues
        );
    }
}
