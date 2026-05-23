package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.review.creator.ReviewTaskCreationResult;
import com.codepilot.module.review.creator.ReviewTaskCreator;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.queue.ReviewTaskMessageDispatcher;
import com.codepilot.module.review.runner.ReviewTaskRunner;
import com.codepilot.module.review.service.ReviewTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewTaskServiceImpl extends ServiceImpl<ReviewTaskMapper, ReviewTask> implements ReviewTaskService {

    private final ReviewTaskCreator reviewTaskCreator;

    private final ReviewTaskMessageDispatcher reviewTaskMessageDispatcher;

    private final ReviewTaskRunner reviewTaskRunner;

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
        ReviewTaskCreationResult creationResult = reviewTaskCreator.create(prUrl, title, reviewCommentMode, headSha);
        if (creationResult.created()) {
            reviewTaskMessageDispatcher.sendAfterCommit(creationResult.taskId());
        }
        return new ReviewCreateResponse(creationResult.taskId(), creationResult.status());
    }

    @Override
    public void processTask(Long taskId) {
        ReviewTask task = getById(taskId);
        if (task == null) {
            throw new BusinessException("review task not found, taskId=" + taskId);
        }
        reviewTaskRunner.run(task);
    }
}
