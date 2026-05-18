package com.codepilot.module.review.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.response.Result;
import com.codepilot.module.review.dto.ReviewCreateRequest;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.service.ReviewFileService;
import com.codepilot.module.review.service.ReviewIssueService;
import com.codepilot.module.review.service.ReviewTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewTaskService reviewTaskService;

    private final ReviewFileService reviewFileService;

    private final ReviewIssueService reviewIssueService;

    @PostMapping
    public Result<ReviewCreateResponse> createReview(@Valid @RequestBody ReviewCreateRequest request) {
        return Result.success(reviewTaskService.createTask(request.getPrUrl()));
    }

    @GetMapping("/{taskId}")
    public Result<ReviewTask> getReviewTask(@PathVariable Long taskId) {
        ReviewTask task = reviewTaskService.getById(taskId);
        if (task == null) {
            throw new BusinessException("review task not found");
        }
        return Result.success(task);
    }

    @GetMapping("/{taskId}/files")
    public Result<List<ReviewFile>> listReviewFiles(@PathVariable Long taskId) {
        List<ReviewFile> files = reviewFileService.list(new LambdaQueryWrapper<ReviewFile>()
                .eq(ReviewFile::getTaskId, taskId)
                .orderByAsc(ReviewFile::getId));
        return Result.success(files);
    }

    @GetMapping("/{taskId}/issues")
    public Result<List<ReviewIssue>> listReviewIssues(@PathVariable Long taskId) {
        List<ReviewIssue> issues = reviewIssueService.list(new LambdaQueryWrapper<ReviewIssue>()
                .eq(ReviewIssue::getTaskId, taskId)
                .orderByDesc(ReviewIssue::getId));
        return Result.success(issues);
    }

    @GetMapping
    public Result<Page<ReviewTask>> pageReviewTasks(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size
    ) {
        Page<ReviewTask> page = reviewTaskService.page(
                Page.of(current, size),
                new LambdaQueryWrapper<ReviewTask>().orderByDesc(ReviewTask::getId)
        );
        return Result.success(page);
    }
}

