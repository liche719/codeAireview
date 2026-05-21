package com.codepilot.module.command.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.codepilot.common.enums.ReviewTaskStatus;
import com.codepilot.common.util.MarkdownSanitizer;
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
            commandTaskLogService.record(task.getId(), "START", true, "修复命令已开始。", null);
            GithubPullRequestDetail detail = githubClient.getPullRequestDetail(
                    task.getRepoOwner(),
                    task.getRepoName(),
                    task.getPrNumber()
            );
            if (!StringUtils.hasText(detail.getHeadSha())) {
                throw new IllegalStateException("PR head sha is required before generating a fix");
            }
            task.setHeadSha(detail.getHeadSha());
            updateById(task);

            assertWritableSameRepo(task, detail);
            List<ReviewIssue> fixableIssues = selectFixableIssues(task);
            if (fixableIssues.isEmpty()) {
                completeFailed(task, "未找到可修复的受支持 review 问题。");
                comment(task, "我没找到支持的可修复问题。请先运行 `@x-pilotx review`，或者把需求收敛到一个具体的代码问题。");
                return;
            }

            String issuesJson = buildIssuesJson(fixableIssues);
            String snippets = buildSnippets(task, detail.getHeadSha(), fixableIssues);
            if (!StringUtils.hasText(snippets)) {
                completeFailed(task, "找到了可修复的问题，但没有有效的代码片段。");
                comment(task, "我找到了可修复的问题，但没有加载到足够的代码上下文来生成安全补丁。");
                return;
            }
            String limits = "maxFiles=" + properties.getFixMaxFiles()
                    + ", maxChangedLines=" + properties.getFixMaxChangedLines()
                    + ", output=unified diff only";
            CodeFixResult fixResult = codeFixService.generateFix(task.getId(), issuesJson, snippets, limits);
            String patch = fixResult == null ? null : fixResult.getPatch();
            if (!StringUtils.hasText(patch)) {
                completeFailed(task, "模型没有生成补丁。");
                comment(task, "我无法为这次请求生成安全补丁。");
                return;
            }

            PatchStats stats = validatePatchScope(patch);
            task.setGeneratedPatch(patch);
            updateById(task);
            commandTaskLogService.record(task.getId(), "PATCH_GENERATED", true, "补丁已生成。", stats.toString());

            GitPatchExecutionRequest request = buildExecutionRequest(task, detail, patch, fixResult == null ? null : fixResult.getCommitMessage());
            GitPatchExecutionResult executionResult = gitPatchExecutor.execute(request);
            if (!executionResult.isSuccess()) {
                completeFailed(task, executionResult.getMessage());
                commandTaskLogService.record(task.getId(), "PATCH_EXECUTE", false, executionResult.getMessage(), executionResult.getDetail());
                comment(task, "我生成了一个补丁，但校验失败，所以没有推送提交。\n\n" + truncate(executionResult.getMessage(), 500));
                return;
            }

            task.setCommitSha(executionResult.getCommitSha());
            completeSuccess(task);
            commandTaskLogService.record(task.getId(), "PATCH_EXECUTE", true, executionResult.getMessage(), executionResult.getDetail());
            if (Boolean.TRUE.equals(task.getDryRun())) {
                comment(task, buildDryRunComment(stats, fixResult.getSummary(), patch));
            } else {
                comment(task, "**CodePilot AI** 已推送修复提交"
                        + (StringUtils.hasText(task.getCommitSha()) ? "：`" + task.getCommitSha() + "`" : "。"));
            }
        } catch (Exception exception) {
            completeFailed(task, exception.getMessage());
            commandTaskLogService.record(task.getId(), "FAILED", false, exception.getMessage(), null);
            comment(task, "我没能完成这次修复请求。\n\n" + truncate(exception.getMessage(), 500));
        }
    }

    private void assertWritableSameRepo(PrCommandTask task, GithubPullRequestDetail detail) {
        String expectedRepo = task.getRepoOwner() + "/" + task.getRepoName();
        if (!expectedRepo.equalsIgnoreCase(detail.getHeadRepoFullName())
                || !expectedRepo.equalsIgnoreCase(detail.getBaseRepoFullName())) {
            throw new IllegalStateException("仅允许对当前仓库中的分支执行修复。");
        }
        if (!StringUtils.hasText(detail.getHeadRef()) || !StringUtils.hasText(detail.getHeadRepoCloneUrl())) {
            throw new IllegalStateException("PR head 分支信息不完整。");
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
            AiReviewResult result = aiReviewService.reviewFile(null, file.getFilename(), file.getPatch(), allChangedFiles);
            if (result == null || result.getIssues() == null) {
                continue;
            }
            for (AiReviewIssue aiIssue : result.getIssues()) {
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
            item.put("issueTypeZh", issue.getIssueTypeZh());
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
                String codeSnippet = snippet(issue.getFilePath(), issue.getLineNumber(), content);
                if (StringUtils.hasText(codeSnippet)) {
                    builder.append(codeSnippet).append("\n");
                }
            } catch (Exception exception) {
                commandTaskLogService.record(task.getId(), "SNIPPET", false,
                        "Failed to load snippet for " + issue.getFilePath(), exception.getMessage());
            }
        }
        return builder.toString();
    }

    private String snippet(String filePath, int lineNumber, String content) {
        if (!StringUtils.hasText(content) || lineNumber < 1) {
            return "";
        }
        String[] lines = content == null ? new String[0] : content.split("\\R", -1);
        if (lineNumber > lines.length) {
            return "";
        }
        int start = Math.max(1, lineNumber - SNIPPET_RADIUS);
        int end = Math.min(lines.length, lineNumber + SNIPPET_RADIUS);
        StringBuilder builder = new StringBuilder();
        builder.append("文件：").append(filePath).append(" 行 ").append(start).append("-").append(end).append("\n");
        for (int i = start; i <= end; i++) {
            builder.append(i).append(": ").append(lines[i - 1]).append("\n");
        }
        return builder.toString();
    }

    private PatchStats validatePatchScope(String patch) {
        PatchStats stats = PatchStats.from(patch);
        if (stats.filesChanged() > properties.getFixMaxFiles()) {
            throw new IllegalStateException("补丁修改的文件数过多：" + stats.filesChanged());
        }
        if (stats.changedLines() > properties.getFixMaxChangedLines()) {
            throw new IllegalStateException("补丁修改的行数过多：" + stats.changedLines());
        }
        return stats;
    }

    private GitPatchExecutionRequest buildExecutionRequest(PrCommandTask task, GithubPullRequestDetail detail, String patch) {
        return buildExecutionRequest(task, detail, patch, null);
    }

    private GitPatchExecutionRequest buildExecutionRequest(PrCommandTask task, GithubPullRequestDetail detail, String patch, String commitMessage) {
        GitPatchExecutionRequest request = new GitPatchExecutionRequest();
        request.setCloneUrl(detail.getHeadRepoCloneUrl());
        request.setBranch(detail.getHeadRef());
        request.setPatch(patch);
        request.setToken(githubToken);
        request.setCommitMessage(resolveCommitMessage(commitMessage));
        request.setValidationCommand(properties.getFixValidationCommand());
        request.setAllowedValidationCommands(properties.getFixAllowedValidationCommands());
        request.setInheritValidationEnvironment(properties.isFixValidationInheritEnvironment());
        request.setValidationTimeoutSeconds(properties.getFixValidationTimeoutSeconds());
        request.setDryRun(Boolean.TRUE.equals(task.getDryRun()));
        return request;
    }

    private String resolveCommitMessage(String modelCommitMessage) {
        if (StringUtils.hasText(modelCommitMessage)) {
            String normalized = modelCommitMessage.replaceAll("\\s+", " ").trim();
            if (normalized.length() > 120) {
                normalized = normalized.substring(0, 120).trim();
            }
            if (StringUtils.hasText(normalized)) {
                return normalized;
            }
        }
        return "fix: CodePilot AI 自动修复";
    }

    private String buildDryRunComment(PatchStats stats, String summary, String patch) {
        return """
                **CodePilot AI** 预演完成。

                摘要：%s

                计划变更：
                - 文件数：%d
                - 变更行数：%d

                ```diff
                %s
                ```
                """.formatted(
                MarkdownSanitizer.sanitizeInlineText(summary, 500, "补丁已生成。"),
                stats.filesChanged(),
                stats.changedLines(),
                MarkdownSanitizer.sanitizeCodeBlockText(patch, 2500, "")
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
                if (line.startsWith("+++ ")) {
                    String filePath = line.substring(4).trim();
                    if (!"/dev/null".equals(filePath)) {
                        files.add(filePath.replaceFirst("^b/", ""));
                    }
                }
                if ((line.startsWith("+") && !line.startsWith("+++"))
                        || (line.startsWith("-") && !line.startsWith("---"))) {
                    changedLines++;
                }
            }
            return new PatchStats(files.isEmpty() && changedLines > 0 ? 1 : files.size(), changedLines);
        }

        @Override
        public String toString() {
            return "filesChanged=" + filesChanged + ", changedLines=" + changedLines;
        }
    }
}
