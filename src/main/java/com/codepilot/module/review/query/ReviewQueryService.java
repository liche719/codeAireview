package com.codepilot.module.review.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.response.PageView;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubLinkedIssue;
import com.codepilot.module.review.dto.ReviewFileView;
import com.codepilot.module.review.dto.ReviewIssueView;
import com.codepilot.module.review.dto.ReviewTaskView;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.service.ReviewFileService;
import com.codepilot.module.review.service.ReviewIssueService;
import com.codepilot.module.review.service.ReviewTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReviewQueryService {

    private final ReviewTaskService reviewTaskService;

    private final ReviewFileService reviewFileService;

    private final ReviewIssueService reviewIssueService;

    private final GithubClient githubClient;

    public ReviewTaskView getTask(Long taskId) {
        return ReviewTaskView.from(requireTask(taskId));
    }

    public List<ReviewFileView> listFiles(Long taskId) {
        return reviewFileService.list(new LambdaQueryWrapper<ReviewFile>()
                        .eq(ReviewFile::getTaskId, taskId)
                        .orderByAsc(ReviewFile::getId))
                .stream()
                .map(ReviewFileView::from)
                .toList();
    }

    public List<ReviewIssueView> listIssues(Long taskId) {
        return reviewIssueService.list(new LambdaQueryWrapper<ReviewIssue>()
                        .eq(ReviewIssue::getTaskId, taskId)
                        .orderByDesc(ReviewIssue::getId))
                .stream()
                .map(ReviewIssueView::from)
                .toList();
    }

    public List<GithubLinkedIssue> listLinkedIssues(Long taskId) {
        ReviewTask task = requireTask(taskId);
        return githubClient.listPullRequestLinkedIssues(
                task.getRepoOwner(), task.getRepoName(), task.getPrNumber()
        );
    }

    public PageView<ReviewTaskView> pageTasks(long current, long size) {
        Page<ReviewTask> entityPage = reviewTaskService.page(
                Page.of(current, size),
                new LambdaQueryWrapper<ReviewTask>().orderByDesc(ReviewTask::getId)
        );
        return new PageView<>(
                entityPage.getRecords().stream().map(ReviewTaskView::from).toList(),
                entityPage.getTotal(),
                entityPage.getSize(),
                entityPage.getCurrent(),
                entityPage.getPages()
        );
    }

    private ReviewTask requireTask(Long taskId) {
        ReviewTask task = reviewTaskService.getById(taskId);
        if (task == null) {
            throw new BusinessException("review task not found");
        }
        return task;
    }
}
