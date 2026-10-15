package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.git.auth.GithubAuthTokenProvider;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubIssueComment;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.report.CommentMarkers;
import com.codepilot.module.review.report.ReviewReportFormatter;
import com.codepilot.module.review.service.GitHubCommentService;
import com.codepilot.module.review.service.GitHubCommentResult;
import com.codepilot.module.review.service.ReviewFileService;
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

    private final ReviewFileService reviewFileService;

    private final GithubClient githubClient;

    private final ReviewReportFormatter reviewReportFormatter;

    private final GithubAuthTokenProvider githubAuthTokenProvider;

    private final boolean commentEnabled;

    /**
     * 身份标记只在评论正文前 N 行内匹配，避免正文里引用到标记造成误判。
     */
    private final int identityHeaderLines;

    public GitHubCommentServiceImpl(
            ReviewTaskMapper reviewTaskMapper,
            ReviewIssueService reviewIssueService,
            ReviewFileService reviewFileService,
            GithubClient githubClient,
            ReviewReportFormatter reviewReportFormatter,
            GithubAuthTokenProvider githubAuthTokenProvider,
            @Value("${codepilot.github.comment-enabled:false}") boolean commentEnabled,
            @Value("${codepilot.github.comment-identity-header-lines:5}") int identityHeaderLines
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewIssueService = reviewIssueService;
        this.reviewFileService = reviewFileService;
        this.githubClient = githubClient;
        this.reviewReportFormatter = reviewReportFormatter;
        this.githubAuthTokenProvider = githubAuthTokenProvider;
        this.commentEnabled = commentEnabled;
        this.identityHeaderLines = identityHeaderLines <= 0
                ? CommentMarkers.DEFAULT_HEADER_LINES
                : identityHeaderLines;
    }

    @Override
    public GitHubCommentResult commentReviewResult(Long taskId) {
        try {
            if (!commentEnabled) {
                log.info("Skip GitHub PR comment because comment is disabled, taskId={}", taskId);
                return GitHubCommentResult.skippedResult();
            }

            ReviewTask task = reviewTaskMapper.selectById(taskId);
            if (task == null) {
                log.warn("Skip GitHub PR comment because review task was not found, taskId={}", taskId);
                return GitHubCommentResult.failedResult("review task was not found");
            }
            if (!githubAuthTokenProvider.canAuthenticate(task.getRepoOwner(), task.getRepoName())) {
                log.warn("Skip GitHub PR comment because GitHub auth is missing, taskId={}", taskId);
                return GitHubCommentResult.skippedResult();
            }

            List<ReviewIssue> issues = reviewIssueService.list(new LambdaQueryWrapper<ReviewIssue>()
                    .eq(ReviewIssue::getTaskId, taskId));
            // 覆盖度信息：哪些文件没被审查、为什么。缺失它会让摘要看起来像"整个 PR 都审过了"。
            List<ReviewFile> reviewFiles = reviewFileService.list(new LambdaQueryWrapper<ReviewFile>()
                    .eq(ReviewFile::getTaskId, taskId));
            String body = reviewReportFormatter.formatMarkdown(task, issues, reviewFiles);
            Optional<Long> existingCommentId = findExistingCommentId(task);
            if (existingCommentId.isPresent()) {
                githubClient.updateIssueComment(task.getRepoOwner(), task.getRepoName(), existingCommentId.get(), body);
                log.info("Updated CodePilot GitHub PR comment, taskId={}, owner={}, repo={}, pullNumber={}, commentId={}",
                        task.getId(), task.getRepoOwner(), task.getRepoName(), task.getPrNumber(), existingCommentId.get());
                return GitHubCommentResult.publishedResult();
            }
            githubClient.createPullRequestComment(task.getRepoOwner(), task.getRepoName(), task.getPrNumber(), body);
            log.info("Created CodePilot GitHub PR comment, taskId={}, owner={}, repo={}, pullNumber={}",
                    task.getId(), task.getRepoOwner(), task.getRepoName(), task.getPrNumber());
            return GitHubCommentResult.publishedResult();
        } catch (Exception exception) {
            String errorMessage = SensitiveDataSanitizer.redactAndTruncate(exception.getMessage(), 2000);
            log.warn("GitHub PR comment failed but ignored, taskId={}, errorType={}, message={}",
                    taskId, exception.getClass().getSimpleName(), errorMessage);
            return GitHubCommentResult.failedResult(errorMessage);
        }
    }

    private Optional<Long> findExistingCommentId(ReviewTask task) {
        String marker = reviewReportFormatter.getCommentMarker();
        if (!StringUtils.hasText(marker)) {
            return Optional.empty();
        }
        String botLogin = githubClient.getAuthenticatedUserLogin();
        return githubClient.listPullRequestComments(task.getRepoOwner(), task.getRepoName(), task.getPrNumber()).stream()
                .filter(comment -> comment != null && comment.getId() != null)
                .filter(comment -> isOwnSummaryComment(comment, marker, botLogin))
                .max(Comparator.comparing(comment -> nullToEmpty(comment.getUpdatedAt())))
                .map(GithubIssueComment::getId);
    }

    /**
     * 只有<b>本机器人发的</b>且<b>带审查摘要身份标记</b>的评论才允许被更新。
     *
     * <p>这两道过滤缺一不可，只做任何一道都会重新引入覆盖缺陷：</p>
     * <ul>
     *   <li>只按标记匹配：机器人其它用途的评论一旦带上同一标记，就会被摘要更新覆盖；</li>
     *   <li>只按作者匹配：摘要会命中机器人发的<b>任意</b>一条评论（例如 Agent 回复），同样覆盖错对象。</li>
     * </ul>
     *
     * <p>作者信息不可用时（接口失败或 payload 未带 user）降级为只按标记判断，而不是拒绝更新——
     * 此时身份标记的分离已经保证了不会误命中其他用途的评论。</p>
     */
    private boolean isOwnSummaryComment(GithubIssueComment comment, String marker, String botLogin) {
        if (!CommentMarkers.matchesHeader(comment.getBody(), marker, identityHeaderLines)) {
            return false;
        }
        if (!StringUtils.hasText(botLogin) || !StringUtils.hasText(comment.getUserLogin())) {
            return true;
        }
        return botLogin.equalsIgnoreCase(comment.getUserLogin());
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
