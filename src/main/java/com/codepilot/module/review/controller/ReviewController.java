package com.codepilot.module.review.controller;

import com.codepilot.common.response.PageView;
import com.codepilot.common.response.Result;
import com.codepilot.module.git.dto.GithubLinkedIssue;
import com.codepilot.module.review.dto.ReviewFileView;
import com.codepilot.module.review.dto.ReviewIssueView;
import com.codepilot.module.review.dto.ReviewTaskView;
import com.codepilot.module.review.dto.ReviewCreateRequest;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.query.ReviewQueryService;
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

    private final ReviewQueryService reviewQueryService;

    /** 审计（2026-10-29）：谁能触发审查要能查得到 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.codepilot.module.audit.service.AuditLogService auditLogService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private jakarta.servlet.http.HttpServletRequest httpRequest;

    @PostMapping
    public Result<ReviewCreateResponse> createReview(@Valid @RequestBody ReviewCreateRequest request) {
        // 2026-10-29：允许调用方指定评论形态。原来 API 触发的一律 SUMMARY_ONLY，
        // 于是"手动触发一次审查"永远拿不到行内评论——端到端验证时才发现（任务 14：20 条问题只出了摘要）。
        com.codepilot.common.enums.ReviewCommentMode commentMode =
                com.codepilot.common.enums.ReviewCommentMode.fromValue(request.getCommentMode());
        ReviewCreateResponse response = reviewTaskService.createTask(request.getPrUrl(), null, commentMode);
        if (auditLogService != null) {
            auditLogService.record(
                    com.codepilot.module.audit.service.AuditLogService.ACTION_TRIGGER_REVIEW,
                    "api-key",
                    httpRequest == null ? null : httpRequest.getRemoteAddr(),
                    request.getPrUrl(),
                    "SUCCESS",
                    "taskId=" + response.getTaskId() + ", commentMode=" + commentMode.name());
        }
        return Result.success(response);
    }

    @GetMapping("/{taskId}")
    public Result<ReviewTaskView> getReviewTask(@PathVariable Long taskId) {
        return Result.success(reviewQueryService.getTask(taskId));
    }

    @GetMapping("/{taskId}/files")
    public Result<List<ReviewFileView>> listReviewFiles(@PathVariable Long taskId) {
        return Result.success(reviewQueryService.listFiles(taskId));
    }

    @GetMapping("/{taskId}/issues")
    public Result<List<ReviewIssueView>> listReviewIssues(@PathVariable Long taskId) {
        return Result.success(reviewQueryService.listIssues(taskId));
    }

    @GetMapping("/{taskId}/linked-issues")
    public Result<List<GithubLinkedIssue>> listLinkedIssues(@PathVariable Long taskId) {
        return Result.success(reviewQueryService.listLinkedIssues(taskId));
    }

    @GetMapping
    public Result<PageView<ReviewTaskView>> pageReviewTasks(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size
    ) {
        return Result.success(reviewQueryService.pageTasks(current, size));
    }
}
