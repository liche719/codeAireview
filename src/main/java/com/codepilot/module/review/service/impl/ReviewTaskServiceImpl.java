package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.common.enums.ReviewTaskStatus;
import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.review.creator.ReviewTaskCreationResult;
import com.codepilot.module.review.creator.ReviewTaskCreator;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.metrics.ReviewMetrics;
import com.codepilot.module.review.queue.ReviewTaskMessageDispatcher;
import com.codepilot.module.review.runner.ReviewTaskRunner;
import com.codepilot.module.review.service.ReviewTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewTaskServiceImpl extends ServiceImpl<ReviewTaskMapper, ReviewTask> implements ReviewTaskService {

    private final ReviewTaskCreator reviewTaskCreator;

    private final ReviewTaskMessageDispatcher reviewTaskMessageDispatcher;

    private final ReviewTaskRunner reviewTaskRunner;

    private final ReviewMetrics reviewMetrics;

    /**
     * 2026-10-29：API 触发的任务原来**不带 headSha 建库**，于是"同一 PR 重复触发"永远匹配不到可复用的任务，
     * 每次插入一条 head_sha 为空的行；消费者回填 head_sha 时撞上唯一索引
     * `uq_review_task_active_head_mode`（已有一个同 head、同 mode 的 SUCCESS 行）→ 任务必然失败。
     *
     * <p>修法：创建前先把 headSha 解析出来（一次 GitHub 调用），让去重/复用真正生效。
     * 解析失败不阻塞创建——退回原来的行为，由消费者阶段报明确错误。
     */
    private final com.codepilot.module.git.client.GithubClient githubClient;

    private final com.codepilot.module.git.parser.GithubPrUrlParser githubPrUrlParser;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewCreateResponse createTask(String prUrl) {
        return createTask(prUrl, null, ReviewCommentMode.SUMMARY_ONLY, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewCreateResponse createTask(String prUrl, String title) {
        return createTask(prUrl, title, ReviewCommentMode.SUMMARY_ONLY, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewCreateResponse createTask(String prUrl, String title, ReviewCommentMode reviewCommentMode) {
        return createTask(prUrl, title, reviewCommentMode, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewCreateResponse createTask(String prUrl, String title, ReviewCommentMode reviewCommentMode, String headSha) {
        String resolvedHeadSha = StringUtils.hasText(headSha) ? headSha : resolveHeadSha(prUrl);
        ReviewTaskCreationResult creationResult = reviewTaskCreator.create(prUrl, title, reviewCommentMode, resolvedHeadSha);
        reviewMetrics.recordTaskCreation(creationResult.created());
        if (creationResult.created()) {
            reviewTaskMessageDispatcher.enqueue(creationResult.taskId());
        }
        return new ReviewCreateResponse(creationResult.taskId(), creationResult.status());
    }


    /** 解析 PR 的 head sha；拿不到就返回 null（不阻塞建任务） */
    private String resolveHeadSha(String prUrl) {
        try {
            com.codepilot.module.git.dto.GithubPrInfo prInfo = githubPrUrlParser.parse(prUrl);
            com.codepilot.module.git.dto.GithubPullRequestDetail detail =
                    githubClient.getPullRequestDetail(prInfo.getOwner(), prInfo.getRepo(), prInfo.getPullNumber());
            String sha = detail == null ? null : detail.getHeadSha();
            if (StringUtils.hasText(sha)) {
                return sha;
            }
        } catch (RuntimeException exception) {
            log.warn("解析 PR head sha 失败，退回建任务后再由消费者获取：{}", exception.getMessage());
        }
        return null;
    }

    @Override
    public void processTask(Long taskId) {
        ReviewTask existingTask = getById(taskId);
        if (existingTask == null) {
            throw new BusinessException("review task not found, taskId=" + taskId);
        }
        ReviewTask task = reviewTaskRunner.claim(taskId);
        if (task == null) {
            reviewMetrics.recordDuplicateDelivery();
            log.info("Review task message skipped because execution was not claimable, taskId={}, status={}",
                    taskId, existingTask.getStatus());
            return;
        }
        reviewMetrics.recordQueueWait(task.getCreatedAt());
        reviewTaskRunner.run(task);
    }
}
