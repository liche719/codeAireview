package com.codepilot.module.review.creator;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.common.enums.ReviewTaskStatus;
import com.codepilot.module.git.dto.GithubPrInfo;
import com.codepilot.module.git.parser.GithubPrUrlParser;
import com.codepilot.module.git.policy.GithubRepositoryPolicy;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewTaskCreator {

    private final GithubPrUrlParser githubPrUrlParser;

    private final GithubRepositoryPolicy githubRepositoryPolicy;

    private final ReviewTaskMapper reviewTaskMapper;

    public ReviewTaskCreationResult create(
            String prUrl,
            String title,
            ReviewCommentMode reviewCommentMode,
            String headSha
    ) {
        GithubPrInfo prInfo = githubPrUrlParser.parse(prUrl);
        githubRepositoryPolicy.assertAllowed(prInfo.getOwner(), prInfo.getRepo());
        ReviewCommentMode normalizedReviewCommentMode = normalizeReviewCommentMode(reviewCommentMode);
        String normalizedHeadSha = normalizeHeadSha(headSha);

        ReviewTask reusableTask = findReusableTask(prInfo, normalizedReviewCommentMode, normalizedHeadSha);
        if (reusableTask != null) {
            log.info("Reuse existing review task, taskId={}, owner={}, repo={}, pullNumber={}, headSha={}, status={}, commentMode={}",
                    reusableTask.getId(),
                    prInfo.getOwner(),
                    prInfo.getRepo(),
                    prInfo.getPullNumber(),
                    normalizedHeadSha,
                    reusableTask.getStatus(),
                    normalizedReviewCommentMode);
            return new ReviewTaskCreationResult(reusableTask.getId(), reusableTask.getStatus(), false);
        }

        ReviewTask task = buildPendingTask(prUrl, title, normalizedReviewCommentMode, normalizedHeadSha, prInfo);
        try {
            reviewTaskMapper.insert(task);
        } catch (DuplicateKeyException exception) {
            ReviewTask concurrentlyCreatedTask = findReusableTask(prInfo, normalizedReviewCommentMode, normalizedHeadSha);
            if (concurrentlyCreatedTask != null) {
                log.info("Reuse concurrently created review task, taskId={}, owner={}, repo={}, pullNumber={}, headSha={}, status={}, commentMode={}",
                        concurrentlyCreatedTask.getId(),
                        prInfo.getOwner(),
                        prInfo.getRepo(),
                        prInfo.getPullNumber(),
                        normalizedHeadSha,
                        concurrentlyCreatedTask.getStatus(),
                        normalizedReviewCommentMode);
                return new ReviewTaskCreationResult(
                        concurrentlyCreatedTask.getId(),
                        concurrentlyCreatedTask.getStatus(),
                        false
                );
            }
            throw exception;
        }
        return new ReviewTaskCreationResult(task.getId(), task.getStatus(), true);
    }

    private ReviewTask buildPendingTask(
            String prUrl,
            String title,
            ReviewCommentMode reviewCommentMode,
            String headSha,
            GithubPrInfo prInfo
    ) {
        LocalDateTime now = LocalDateTime.now();
        ReviewTask task = new ReviewTask();
        task.setRepoOwner(prInfo.getOwner());
        task.setRepoName(prInfo.getRepo());
        task.setPrNumber(prInfo.getPullNumber());
        task.setPrUrl(prUrl.trim());
        task.setTitle(title);
        task.setHeadSha(headSha);
        task.setReviewCommentMode(reviewCommentMode.name());
        task.setStatus(ReviewTaskStatus.PENDING.name());
        task.setTotalFiles(0);
        task.setTotalIssues(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    private ReviewTask findReusableTask(GithubPrInfo prInfo, ReviewCommentMode reviewCommentMode, String headSha) {
        if (prInfo == null || !StringUtils.hasText(headSha)) {
            return null;
        }
        List<ReviewTask> tasks = reviewTaskMapper.selectList(new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getRepoOwner, prInfo.getOwner())
                .eq(ReviewTask::getRepoName, prInfo.getRepo())
                .eq(ReviewTask::getPrNumber, prInfo.getPullNumber())
                .eq(ReviewTask::getHeadSha, headSha)
                .eq(ReviewTask::getReviewCommentMode, reviewCommentMode.name())
                .in(ReviewTask::getStatus, List.of(
                        ReviewTaskStatus.PENDING.name(),
                        ReviewTaskStatus.RUNNING.name(),
                        ReviewTaskStatus.SUCCESS.name()
                ))
                .orderByDesc(ReviewTask::getId)
                .last("LIMIT 1"));
        return tasks == null || tasks.isEmpty() ? null : tasks.getFirst();
    }

    private ReviewCommentMode normalizeReviewCommentMode(ReviewCommentMode reviewCommentMode) {
        return reviewCommentMode == null ? ReviewCommentMode.SUMMARY_ONLY : reviewCommentMode;
    }

    private String normalizeHeadSha(String headSha) {
        return StringUtils.hasText(headSha) ? headSha.trim() : null;
    }
}
