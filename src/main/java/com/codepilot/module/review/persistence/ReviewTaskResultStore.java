package com.codepilot.module.review.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.processor.ReviewTaskProcessingResult;
import com.codepilot.module.review.service.ReviewFileService;
import com.codepilot.module.review.service.ReviewIssueService;
import com.codepilot.module.review.state.ReviewTaskStateManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ReviewTaskResultStore {

    private final ReviewFileService reviewFileService;

    private final ReviewIssueService reviewIssueService;

    private final ReviewTaskStateManager reviewTaskStateManager;

    @Transactional(rollbackFor = Exception.class)
    public void replaceAndComplete(ReviewTask task, ReviewTaskProcessingResult result) {
        reviewFileService.remove(new LambdaQueryWrapper<ReviewFile>()
                .eq(ReviewFile::getTaskId, task.getId()));
        reviewIssueService.remove(new LambdaQueryWrapper<ReviewIssue>()
                .eq(ReviewIssue::getTaskId, task.getId()));

        if (!result.reviewFiles().isEmpty()) {
            reviewFileService.saveBatch(result.reviewFiles());
        }
        if (!result.reviewIssues().isEmpty()) {
            reviewIssueService.saveBatch(result.reviewIssues());
        }

        reviewTaskStateManager.markSuccess(
                task,
                result.totalFiles(),
                result.totalIssues(),
                result.riskLevel()
        );
    }
}
