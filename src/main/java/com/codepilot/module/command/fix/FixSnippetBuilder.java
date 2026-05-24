package com.codepilot.module.command.fix;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.review.entity.ReviewIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class FixSnippetBuilder {

    private static final int SNIPPET_RADIUS = 20;

    private final GithubClient githubClient;

    private final PrCommandTaskLogService commandTaskLogService;

    public String build(PrCommandTask task, String ref, List<ReviewIssue> issues) {
        StringBuilder builder = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        if (issues == null) {
            return "";
        }
        for (ReviewIssue issue : issues) {
            if (issue == null || !StringUtils.hasText(issue.getFilePath()) || issue.getLineNumber() == null) {
                continue;
            }
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
                        "Failed to load snippet for " + issue.getFilePath(),
                        SensitiveDataSanitizer.redact(exception.getMessage()));
            }
        }
        return builder.toString();
    }

    String snippet(String filePath, int lineNumber, String content) {
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
}
