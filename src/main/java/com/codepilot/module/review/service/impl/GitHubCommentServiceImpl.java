package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubIssueComment;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.report.ReviewReportFormatter;
import com.codepilot.module.review.service.GitHubCommentService;
import com.codepilot.module.review.service.ReviewIssueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
public class GitHubCommentServiceImpl implements GitHubCommentService {

    private final ReviewTaskMapper reviewTaskMapper;

    private final ReviewIssueService reviewIssueService;

    private final GithubClient githubClient;

    private final ReviewReportFormatter reviewReportFormatter;

    private final boolean commentEnabled;

    private final String githubToken;

    private final String commentMarker;

    public GitHubCommentServiceImpl(
            ReviewTaskMapper reviewTaskMapper,
            ReviewIssueService reviewIssueService,
            GithubClient githubClient,
            ReviewReportFormatter reviewReportFormatter,
            @Value("${codepilot.github.comment-enabled:false}") boolean commentEnabled,
            @Value("${codepilot.github.token:}") String githubToken,
            @Value("${codepilot.github.comment-marker:}") String commentMarker
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewIssueService = reviewIssueService;
        this.githubClient = githubClient;
        this.reviewReportFormatter = reviewReportFormatter;
        this.commentEnabled = commentEnabled;
        this.githubToken = githubToken;
        this.commentMarker = StringUtils.hasText(commentMarker)
                ? commentMarker
                : ReviewReportFormatter.DEFAULT_COMMENT_MARKER;
    }

    @Override
    public void commentReviewResult(Long taskId) {
        try {
            if (!commentEnabled) {
                log.info("Skip GitHub PR comment because comment is disabled, taskId={}", taskId);
                return;
            }
            if (!StringUtils.hasText(githubToken)) {
                log.warn("Skip GitHub PR comment because GitHub token is missing, taskId={}", taskId);
                return;
            }

            ReviewTask task = reviewTaskMapper.selectById(taskId);
            if (task == null) {
                log.warn("Skip GitHub PR comment because review task was not found, taskId={}", taskId);
                return;
            }

            List<ReviewIssue> issues = reviewIssueService.list(new LambdaQueryWrapper<ReviewIssue>()
                    .eq(ReviewIssue::getTaskId, taskId));
            String body = reviewReportFormatter.formatMarkdown(task, issues);
            GithubIssueComment existingComment = findExistingCodePilotComment(task);
            if (existingComment == null) {
                githubClient.createPullRequestComment(task.getRepoOwner(), task.getRepoName(), task.getPrNumber(), body);
                log.info("Created new CodePilot GitHub PR comment, taskId={}, owner={}, repo={}, pullNumber={}",
                        task.getId(), task.getRepoOwner(), task.getRepoName(), task.getPrNumber());
            } else {
                githubClient.updateIssueComment(task.getRepoOwner(), task.getRepoName(), existingComment.getId(), body);
                log.info("Updated existing CodePilot GitHub PR comment, taskId={}, owner={}, repo={}, pullNumber={}, commentId={}",
                        task.getId(), task.getRepoOwner(), task.getRepoName(), task.getPrNumber(), existingComment.getId());
            }
        } catch (Exception exception) {
            log.warn("GitHub PR comment failed but ignored, taskId={}, message={}", taskId, exception.getMessage());
        }
    }

    private GithubIssueComment findExistingCodePilotComment(ReviewTask task) {
        List<GithubIssueComment> comments = githubClient.listPullRequestComments(
                task.getRepoOwner(),
                task.getRepoName(),
                task.getPrNumber()
        );
        return comments.stream()
                .filter(comment -> comment != null && comment.getId() != null && StringUtils.hasText(comment.getBody()))
                .filter(comment -> comment.getBody().contains(commentMarker))
                .findFirst()
                .orElse(null);
    }
}
