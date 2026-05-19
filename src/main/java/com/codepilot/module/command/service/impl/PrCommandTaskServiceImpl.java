package com.codepilot.module.command.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.codepilot.common.enums.ReviewTaskStatus;
import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.dto.CodeFixResult;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.agent.service.CodeFixService;
import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.git.GitPatchExecutionRequest;
import com.codepilot.module.command.git.GitPatchExecutionResult;
import com.codepilot.module.command.git.GitPatchExecutor;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.command.service.PrCommandTaskService;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.service.ReviewIssueService;
import com.codepilot.module.review.service.ReviewTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrCommandTaskServiceImpl extends ServiceImpl<PrCommandTaskMapper, PrCommandTask>
        implements PrCommandTaskService {

    private static final Set<String> FIXABLE_TYPES = Set.of(
            "SQL_RISK",
            "SECURITY",
            "LOGGING",
            "EXCEPTION_HANDLING",
            "BUG_RISK"
    );

    private static final int SNIPPET_RADIUS = 20;

    private final GithubCommandProperties properties;

    private final GithubClient githubClient;

    private final ReviewTaskService reviewTaskService;

    private final ReviewIssueService reviewIssueService;

    private final AiReviewService aiReviewService;

    private final CodeFixService codeFixService;

    private final GitPatchExecutor gitPatchExecutor;

    private final PrCommandTaskLogService commandTaskLogService;

    private final ObjectMapper objectMapper;

    @Value("${codepilot.github.token:}")
    private String githubToken;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrCommandTask createFixTask(GitHubPullRequestWebhookPayload payload) {
        PrCommandTask task = new PrCommandTask();
        task.setCommandType("FIX");
        task.setStatus("PENDING");
        task.setRepoOwner(payload.getOwner());
        task.setRepoName(payload.getRepo());
        task.setPrNumber(payload.getPullNumber());
        task.setPrUrl(payload.getPrUrl());
        task.setTitle(payload.getTitle());
        task.setHeadSha(payload.getHeadSha());
        task.setCommentId(payload.getCommentId());
        task.setCommentBody(payload.getCommentBody());
        task.setCommentUserLogin(payload.getCommentUserLogin());
        task.setDryRun(Boolean.TRUE.equals(payload.getDryRun()));
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        save(task);
        return task;
    }

    @Override
    public void processFixTask(Long commandTaskId) {
        PrCommandTask task = getById(commandTaskId);
        if (task == null) {
            log.warn("PR command task not found, commandTaskId={}", commandTaskId);
            return;
        }

        markRunning(task);
        try {
            commandTaskLogService.record(task.getId(), "START", true, "Fix command started.", null);
            GithubPullRequestDetail detail = githubClient.getPullRequestDetail(
                    task.getRepoOwner(),
                    task.getRepoName(),
                    task.getPrNumber()
            );
            task.setHeadSha(detail.getHeadSha());
            updateById(task);

            assertWritableSameRepo(task, detail);
            List<ReviewIssue> fixableIssues = selectFixableIssues(task);
            if (fixableIssues.isEmpty()) {
                completeFailed(task, "No supported review issue found to fix.");
                comment(task, "I could not find a supported issue to fix. Please run `@x-pilotx review` first, or narrow the request to a simple code issue.");
                return;
            }

            String issuesJson = buildIssuesJson(fixableIssues);
            String snippets = buildSnippets(task, detail.getHeadSha(), fixableIssues);
            String limits = "maxFiles=" + properties.getFixMaxFiles()
                    + ", maxChangedLines=" + properties.getFixMaxChangedLines()
                    + ", output=unified diff only";
            CodeFixResult fixResult = codeFixService.generateFix(task.getId(), issuesJson, snippets, limits);
            String patch = fixResult == null ? null : fixResult.getPatch();
            if (!StringUtils.hasText(patch)) {
                completeFailed(task, "Model did not generate a patch.");
                comment(task, "I could not generate a safe patch for this request.");
                return;
            }

            PatchStats stats = validatePatchScope(patch);
            task.setGeneratedPatch(patch);
            updateById(task);
            commandTaskLogService.record(task.getId(), "PATCH_GENERATED", true, "Patch generated.", stats.toString());

            GitPatchExecutionRequest request = buildExecutionRequest(task, detail, patch);
            GitPatchExecutionResult executionResult = gitPatchExecutor.execute(request);
            if (!executionResult.isSuccess()) {
                completeFailed(task, executionResult.getMessage());
                commandTaskLogService.record(task.getId(), "PATCH_EXECUTE", false, executionResult.getMessage(), executionResult.getDetail());
                comment(task, "I generated a patch, but validation failed, so I did not push a commit.\n\n" + truncate(executionResult.getMessage(), 500));
                return;
            }

            task.setCommitSha(executionResult.getCommitSha());
            completeSuccess(task);
            commandTaskLogService.record(task.getId(), "PATCH_EXECUTE", true, executionResult.getMessage(), executionResult.getDetail());
            if (Boolean.TRUE.equals(task.getDryRun())) {
                comment(task, buildDryRunComment(stats, fixResult.getSummary(), patch));
            } else {
                comment(task, "**CodePilot AI** pushed a fix commit"
                        + (StringUtils.hasText(task.getCommitSha()) ? ": `" + task.getCommitSha() + "`" : "."));
            }
        } catch (Exception exception) {
            completeFailed(task, exception.getMessage());
            commandTaskLogService.record(task.getId(), "FAILED", false, exception.getMessage(), null);
            comment(task, "I could not complete the fix request.\n\n" + truncate(exception.getMessage(), 500));
        }
    }

    private void assertWritableSameRepo(PrCommandTask task, GithubPullRequestDetail detail) {
        String expectedRepo = task.getRepoOwner() + "/" + task.getRepoName();
        if (!expectedRepo.equalsIgnoreCase(detail.getHeadRepoFullName())
                || !expectedRepo.equalsIgnoreCase(detail.getBaseRepoFullName())) {
            throw new IllegalStateException("Fix is only allowed for branches in the current repository.");
        }
        if (!StringUtils.hasText(detail.getHeadRef()) || !StringUtils.hasText(detail.getHeadRepoCloneUrl())) {
            throw new IllegalStateException("PR head branch information is incomplete.");
        }
    }

    private List<ReviewIssue> selectFixableIssues(PrCommandTask task) {
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
        ReviewTask latestTask = reviewTaskService.getOne(new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getRepoOwner, task.getRepoOwner())
                .eq(ReviewTask::getRepoName, task.getRepoName())
                .eq(ReviewTask::getPrNumber, task.getPrNumber())
                .eq(ReviewTask::getStatus, ReviewTaskStatus.SUCCESS.name())
                .orderByDesc(ReviewTask::getId)
                .last("LIMIT 1"));
        if (latestTask == null) {
            return List.of();
        }
        commandTaskLogService.record(task.getId(), "CONTEXT", true,
                "Using latest successful review task " + latestTask.getId(), null);
        return reviewIssueService.list(new LambdaQueryWrapper<ReviewIssue>()
                .eq(ReviewIssue::getTaskId, latestTask.getId())
                .orderByAsc(ReviewIssue::getId));
    }

    private List<ReviewIssue> runAdHocReview(PrCommandTask task) {
        commandTaskLogService.record(task.getId(), "CONTEXT", true, "No successful review task found; running ad-hoc review.", null);
        List<GithubChangedFile> files = githubClient.listPullRequestFiles(task.getRepoOwner(), task.getRepoName(), task.getPrNumber());
        List<String> allChangedFiles = files.stream()
                .map(GithubChangedFile::getFilename)
                .toList();
        List<ReviewIssue> issues = new ArrayList<>();
        for (GithubChangedFile file : files) {
            if (issues.size() >= properties.getFixMaxFiles()) {
                break;
            }
            if (!StringUtils.hasText(file.getPatch())) {
                continue;
            }
            AiReviewResult result = aiReviewService.reviewFile(null, file.getFilename(), file.getPatch(), allChangedFiles);
            if (result == null || result.getIssues() == null) {
                continue;
            }
            for (AiReviewIssue aiIssue : result.getIssues()) {
                ReviewIssue issue = new ReviewIssue();
                issue.setFilePath(StringUtils.hasText(aiIssue.getFilePath()) ? aiIssue.getFilePath() : file.getFilename());
                issue.setLineNumber(aiIssue.getLineNumber());
                issue.setIssueType(aiIssue.getIssueType());
                issue.setSeverity(aiIssue.getSeverity());
                issue.setTitle(aiIssue.getTitle());
                issue.setDescription(aiIssue.getDescription());
                issue.setSuggestion(aiIssue.getSuggestion());
                issue.setSource(aiIssue.getSource());
                issue.setRuleReference(aiIssue.getRuleReference());
                issues.add(issue);
            }
        }
        return issues;
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

    private String buildIssuesJson(List<ReviewIssue> issues) throws Exception {
        List<Map<String, Object>> promptIssues = new ArrayList<>();
        for (ReviewIssue issue : issues) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("filePath", issue.getFilePath());
            item.put("lineNumber", issue.getLineNumber());
            item.put("issueType", issue.getIssueType());
            item.put("severity", issue.getSeverity());
            item.put("title", issue.getTitle());
            item.put("description", issue.getDescription());
            item.put("suggestion", issue.getSuggestion());
            promptIssues.add(item);
        }
        return objectMapper.writeValueAsString(promptIssues);
    }

    private String buildSnippets(PrCommandTask task, String ref, List<ReviewIssue> issues) {
        StringBuilder builder = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        for (ReviewIssue issue : issues) {
            String key = issue.getFilePath() + ":" + issue.getLineNumber();
            if (!seen.add(key)) {
                continue;
            }
            try {
                String content = githubClient.getFileContent(task.getRepoOwner(), task.getRepoName(), issue.getFilePath(), ref);
                builder.append(snippet(issue.getFilePath(), issue.getLineNumber(), content)).append("\n");
            } catch (Exception exception) {
                commandTaskLogService.record(task.getId(), "SNIPPET", false,
                        "Failed to load snippet for " + issue.getFilePath(), exception.getMessage());
            }
        }
        return builder.toString();
    }

    private String snippet(String filePath, int lineNumber, String content) {
        String[] lines = content == null ? new String[0] : content.split("\\R", -1);
        int start = Math.max(1, lineNumber - SNIPPET_RADIUS);
        int end = Math.min(lines.length, lineNumber + SNIPPET_RADIUS);
        StringBuilder builder = new StringBuilder();
        builder.append("File: ").append(filePath).append(" lines ").append(start).append("-").append(end).append("\n");
        for (int i = start; i <= end; i++) {
            builder.append(i).append(": ").append(lines[i - 1]).append("\n");
        }
        return builder.toString();
    }

    private PatchStats validatePatchScope(String patch) {
        PatchStats stats = PatchStats.from(patch);
        if (stats.filesChanged() > properties.getFixMaxFiles()) {
            throw new IllegalStateException("Patch changes too many files: " + stats.filesChanged());
        }
        if (stats.changedLines() > properties.getFixMaxChangedLines()) {
            throw new IllegalStateException("Patch changes too many lines: " + stats.changedLines());
        }
        return stats;
    }

    private GitPatchExecutionRequest buildExecutionRequest(PrCommandTask task, GithubPullRequestDetail detail, String patch) {
        GitPatchExecutionRequest request = new GitPatchExecutionRequest();
        request.setCloneUrl(detail.getHeadRepoCloneUrl());
        request.setBranch(detail.getHeadRef());
        request.setPatch(patch);
        request.setToken(githubToken);
        request.setCommitMessage("fix: apply CodePilot AI suggestions");
        request.setValidationCommand(properties.getFixValidationCommand());
        request.setDryRun(Boolean.TRUE.equals(task.getDryRun()));
        return request;
    }

    private String buildDryRunComment(PatchStats stats, String summary, String patch) {
        return """
                **CodePilot AI** dry-run completed.

                Summary: %s

                Planned changes:
                - Files: %d
                - Changed lines: %d

                ```diff
                %s
                ```
                """.formatted(
                StringUtils.hasText(summary) ? truncate(summary, 500) : "Patch generated.",
                stats.filesChanged(),
                stats.changedLines(),
                truncate(patch, 2500)
        );
    }

    private void markRunning(PrCommandTask task) {
        task.setStatus("RUNNING");
        task.setStartedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        updateById(task);
    }

    private void completeSuccess(PrCommandTask task) {
        task.setStatus("SUCCESS");
        task.setErrorMessage(null);
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        updateById(task);
    }

    private void completeFailed(PrCommandTask task, String message) {
        task.setStatus("FAILED");
        task.setErrorMessage(truncate(message, 2000));
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        updateById(task);
    }

    private void comment(PrCommandTask task, String body) {
        try {
            githubClient.createPullRequestComment(task.getRepoOwner(), task.getRepoName(), task.getPrNumber(), body);
        } catch (Exception exception) {
            log.warn("Failed to comment PR command result, commandTaskId={}, message={}", task.getId(), exception.getMessage());
        }
    }

    private String truncate(String content, int maxLength) {
        if (!StringUtils.hasText(content) || content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }

    private record PatchStats(int filesChanged, int changedLines) {

        private static PatchStats from(String patch) {
            Set<String> files = new LinkedHashSet<>();
            int changedLines = 0;
            for (String line : patch.split("\\R")) {
                if (line.startsWith("diff --git ")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 4) {
                        files.add(parts[3].replaceFirst("^b/", ""));
                    }
                }
                if ((line.startsWith("+") && !line.startsWith("+++"))
                        || (line.startsWith("-") && !line.startsWith("---"))) {
                    changedLines++;
                }
            }
            return new PatchStats(Math.max(files.size(), 1), changedLines);
        }

        @Override
        public String toString() {
            return "filesChanged=" + filesChanged + ", changedLines=" + changedLines;
        }
    }
}
