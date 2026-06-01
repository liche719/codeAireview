package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.git.auth.GithubAuthTokenProvider;
import com.codepilot.module.git.client.GithubClient;
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

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class GitHubCommentServiceImpl implements GitHubCommentService {

    private final ReviewTaskMapper reviewTaskMapper;

    private final ReviewIssueService reviewIssueService;

    private final GithubClient githubClient;

    private final ReviewReportFormatter reviewReportFormatter;

    private final GithubAuthTokenProvider githubAuthTokenProvider;

    private final boolean commentEnabled;

    public GitHubCommentServiceImpl(
            ReviewTaskMapper reviewTaskMapper,
            ReviewIssueService reviewIssueService,
            GithubClient githubClient,
            ReviewReportFormatter reviewReportFormatter,
            GithubAuthTokenProvider githubAuthTokenProvider,
            @Value("${codepilot.github.comment-enabled:false}") boolean commentEnabled
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewIssueService = reviewIssueService;
        this.githubClient = githubClient;
        this.reviewReportFormatter = reviewReportFormatter;
        this.githubAuthTokenProvider = githubAuthTokenProvider;
        this.commentEnabled = commentEnabled;
    }

    @Override
    public void commentReviewResult(Long taskId) {
        try {
            if (!commentEnabled) {
                log.info("Skip GitHub PR comment because comment is disabled, taskId={}", taskId);
                return;
            }

            ReviewTask task = reviewTaskMapper.selectById(taskId);
            if (task == null) {
                log.warn("Skip GitHub PR comment because review task was not found, taskId={}", taskId);
                return;
            }
            if (!githubAuthTokenProvider.canAuthenticate(task.getRepoOwner(), task.getRepoName())) {
                log.warn("Skip GitHub PR comment because GitHub auth is missing, taskId={}", taskId);
                return;
            }

            List<ReviewIssue> issues = reviewIssueService.list(new LambdaQueryWrapper<ReviewIssue>()
                    .eq(ReviewIssue::getTaskId, taskId));
            String body = reviewReportFormatter.formatMarkdown(task, issues);
            Optional<Long> existingCommentId = findExistingCommentId(task);
            if (existingCommentId.isPresent()) {
                githubClient.updateIssueComment(task.getRepoOwner(), task.getRepoName(), existingCommentId.get(), body);
                log.info("Updated CodePilot GitHub PR comment, taskId={}, owner={}, repo={}, pullNumber={}, commentId={}",
                        task.getId(), task.getRepoOwner(), task.getRepoName(), task.getPrNumber(), existingCommentId.get());
                return;
            }
            githubClient.createPullRequestComment(task.getRepoOwner(), task.getRepoName(), task.getPrNumber(), body);
            log.info("Created CodePilot GitHub PR comment, taskId={}, owner={}, repo={}, pullNumber={}",
                    task.getId(), task.getRepoOwner(), task.getRepoName(), task.getPrNumber());
        } catch (Exception exception) {
            log.warn("GitHub PR comment failed but ignored, taskId={}, errorType={}, message={}",
                    taskId, exception.getClass().getSimpleName(), SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }

    private Optional<Long> findExistingCommentId(ReviewTask task) {
        String marker = reviewReportFormatter.getCommentMarker();
        if (!StringUtils.hasText(marker)) {
            return Optional.empty();
        }
        return githubClient.listPullRequestComments(task.getRepoOwner(), task.getRepoName(), task.getPrNumber()).stream()
                .filter(comment -> comment != null && comment.getId() != null)
                .filter(comment -> StringUtils.hasText(comment.getBody()) && comment.getBody().contains(marker))
                .max(Comparator.comparing(comment -> nullToEmpty(comment.getUpdatedAt())))
                .map(com.codepilot.module.git.dto.GithubIssueComment::getId);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
