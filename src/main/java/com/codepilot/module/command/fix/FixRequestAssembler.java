package com.codepilot.module.command.fix;

import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.git.GitPatchExecutionRequest;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.review.entity.ReviewIssue;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class FixRequestAssembler {

    private final GithubCommandProperties properties;

    private final ObjectMapper objectMapper;

    @Value("${codepilot.github.token:}")
    private String githubToken;

    public FixPromptInput buildPromptInput(List<ReviewIssue> issues) throws Exception {
        return new FixPromptInput(
                buildIssuesJson(issues),
                allowedFixPaths(issues),
                "maxFiles=" + properties.getFixMaxFiles()
                        + ", maxChangedLines=" + properties.getFixMaxChangedLines()
                        + ", output=unified diff only"
        );
    }

    public GitPatchExecutionRequest buildExecutionRequest(
            PrCommandTask task,
            GithubPullRequestDetail detail,
            String patch,
            String commitMessage,
            Set<String> allowedPaths
    ) {
        GitPatchExecutionRequest request = new GitPatchExecutionRequest();
        request.setCloneUrl(detail.getHeadRepoCloneUrl());
        request.setBranch(detail.getHeadRef());
        request.setPatch(patch);
        request.setAllowedPaths(allowedPaths == null ? Set.of() : new LinkedHashSet<>(allowedPaths));
        request.setToken(githubToken);
        request.setCommitMessage(resolveCommitMessage(commitMessage));
        request.setValidationCommand(properties.getFixValidationCommand());
        request.setAllowedValidationCommands(properties.getFixAllowedValidationCommands());
        request.setAllowBuildValidationCommands(properties.isFixValidationAllowBuildCommands());
        request.setInheritValidationEnvironment(properties.isFixValidationInheritEnvironment());
        request.setValidationTimeoutSeconds(properties.getFixValidationTimeoutSeconds());
        request.setDryRun(Boolean.TRUE.equals(task.getDryRun()));
        return request;
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

    private Set<String> allowedFixPaths(List<ReviewIssue> fixableIssues) {
        Set<String> paths = new LinkedHashSet<>();
        if (fixableIssues == null) {
            return paths;
        }
        for (ReviewIssue issue : fixableIssues) {
            String path = issue == null ? null : issue.getFilePath();
            if (StringUtils.hasText(path)) {
                paths.add(path.trim());
            }
        }
        return paths;
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
}
