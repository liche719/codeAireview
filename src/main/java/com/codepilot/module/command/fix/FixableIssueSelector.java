package com.codepilot.module.command.fix;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.common.enums.ReviewTaskStatus;
import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.agent.dto.AiReviewRequest;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.service.ReviewIssueService;
import com.codepilot.module.review.service.ReviewTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class FixableIssueSelector {

    private static final Set<String> FIXABLE_TYPES = Set.of(
            "SQL_RISK",
            "SECURITY",
            "LOGGING",
            "EXCEPTION_HANDLING",
            "BUG_RISK"
    );

    private final GithubCommandProperties properties;

    private final GithubClient githubClient;

    private final ReviewTaskService reviewTaskService;

    private final ReviewIssueService reviewIssueService;

    private final AiReviewService aiReviewService;

    private final PrCommandTaskLogService commandTaskLogService;

    public List<ReviewIssue> select(PrCommandTask task) {
        List<ReviewIssue> issues = loadLatestReviewIssues(task);
        if (issues.isEmpty()) {
            issues = runAdHocReview(task);
        }
        List<ReviewIssue> selected = new ArrayList<>();
        Set<String> files = new LinkedHashSet<>();
        for (ReviewIssue issue : issues) {
            if (!isFixable(issue)) {
                continue;
            }
            if (!files.contains(issue.getFilePath()) && files.size() >= properties.getFixMaxFiles()) {
                continue;
            }
            files.add(issue.getFilePath());
            selected.add(issue);
        }
        return selected;
    }

    private List<ReviewIssue> loadLatestReviewIssues(PrCommandTask task) {
        if (!StringUtils.hasText(task.getHeadSha())) {
            commandTaskLogService.record(task.getId(), "CONTEXT", false,
                    "当前 PR head sha 缺失，跳过历史审查结果以避免 stale fix。", null);
            return List.of();
        }
        ReviewTask latestTask = reviewTaskService.getOne(new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getRepoOwner, task.getRepoOwner())
                .eq(ReviewTask::getRepoName, task.getRepoName())
                .eq(ReviewTask::getPrNumber, task.getPrNumber())
                .eq(ReviewTask::getHeadSha, task.getHeadSha())
                .eq(ReviewTask::getStatus, ReviewTaskStatus.SUCCESS.name())
                .orderByDesc(ReviewTask::getId)
                .last("LIMIT 1"));
        if (latestTask == null) {
            return List.of();
        }
        if (!hasSameHeadSha(task, latestTask)) {
            commandTaskLogService.record(task.getId(), "CONTEXT", false,
                    "最近一次成功审查与当前 PR head sha 不一致，跳过历史审查结果以避免 stale fix。", null);
            return List.of();
        }
        commandTaskLogService.record(task.getId(), "CONTEXT", true,
                "使用最近一次成功的审查任务 " + latestTask.getId(), null);
        return reviewIssueService.list(new LambdaQueryWrapper<ReviewIssue>()
                .eq(ReviewIssue::getTaskId, latestTask.getId())
                .orderByAsc(ReviewIssue::getId));
    }

    boolean hasSameHeadSha(PrCommandTask task, ReviewTask reviewTask) {
        return task != null
                && reviewTask != null
                && StringUtils.hasText(task.getHeadSha())
                && StringUtils.hasText(reviewTask.getHeadSha())
                && task.getHeadSha().trim().equalsIgnoreCase(reviewTask.getHeadSha().trim());
    }

    private List<ReviewIssue> runAdHocReview(PrCommandTask task) {
        commandTaskLogService.record(task.getId(), "CONTEXT", true, "未找到成功的审查任务，改为执行一次临时审查。", null);
        List<GithubChangedFile> files = githubClient.listPullRequestFiles(task.getRepoOwner(), task.getRepoName(), task.getPrNumber());
        List<String> allChangedFiles = files.stream()
                .map(GithubChangedFile::getFilename)
                .toList();
        List<ReviewIssue> issues = new ArrayList<>();
        int reviewedFiles = 0;
        for (GithubChangedFile file : files) {
            if (reviewedFiles >= properties.getFixMaxFiles()) {
                break;
            }
            if (!StringUtils.hasText(file.getPatch())) {
                continue;
            }
            reviewedFiles++;
            AiReviewResult result = aiReviewService.reviewFile(new AiReviewRequest(
                    null,
                    file.getFilename(),
                    file.getPatch(),
                    allChangedFiles
            ));
            if (result == null || result.getIssues() == null) {
                continue;
            }
            for (AiReviewIssue aiIssue : result.getIssues()) {
                issues.add(toReviewIssue(file, aiIssue));
            }
        }
        return issues;
    }

    private ReviewIssue toReviewIssue(GithubChangedFile file, AiReviewIssue aiIssue) {
        ReviewIssue issue = new ReviewIssue();
        issue.setFilePath(StringUtils.hasText(aiIssue.getFilePath()) ? aiIssue.getFilePath() : file.getFilename());
        issue.setLineNumber(aiIssue.getLineNumber());
        issue.setIssueType(aiIssue.getIssueType());
        issue.setIssueTypeZh(aiIssue.getIssueTypeZh());
        issue.setSeverity(aiIssue.getSeverity());
        issue.setTitle(aiIssue.getTitle());
        issue.setDescription(aiIssue.getDescription());
        issue.setSuggestion(aiIssue.getSuggestion());
        issue.setSource(aiIssue.getSource());
        issue.setRuleReference(aiIssue.getRuleReference());
        return issue;
    }

    private boolean isFixable(ReviewIssue issue) {
        if (issue == null || !StringUtils.hasText(issue.getFilePath()) || issue.getLineNumber() == null) {
            return false;
        }
        String type = StringUtils.hasText(issue.getIssueType())
                ? issue.getIssueType().trim().toUpperCase(Locale.ROOT)
                : "";
        return FIXABLE_TYPES.contains(type);
    }
}
