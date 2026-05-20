package com.codepilot.module.command.tool;

import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
@RequiredArgsConstructor
public class GithubCommandChatTool {

    private static final int MAX_FILES = 10;

    private static final int MAX_PATCH_CHARS_PER_FILE = 800;

    private static final int MAX_CONTEXT_CHARS = 6000;

    private final GithubClient githubClient;

    @Tool("Get the current GitHub PR basic info, including title, URL, branch, head sha, and repository information")
    public String getPullRequestDetail(
            @P("repository owner") String owner,
            @P("repository name") String repo,
            @P("PR number") Integer pullNumber
    ) {
        GithubPullRequestDetail detail = githubClient.getPullRequestDetail(owner, repo, pullNumber);
        StringBuilder context = new StringBuilder();
        appendLine(context, "PR title: " + safeText(detail.getTitle(), "N/A"));
        appendLine(context, "PR url: " + safeText(detail.getHtmlUrl(), buildFallbackPrUrl(owner, repo, pullNumber)));
        appendLine(context, "PR branch: " + safeText(detail.getHeadRef(), "N/A"));
        appendLine(context, "head sha: " + safeText(detail.getHeadSha(), "N/A"));
        appendLine(context, "head repo: " + safeText(detail.getHeadRepoFullName(), "N/A"));
        appendLine(context, "base repo: " + safeText(detail.getBaseRepoFullName(), "N/A"));
        return truncate(context.toString(), MAX_CONTEXT_CHARS);
    }

    @Tool("Get the current GitHub PR changed files list and patch summary")
    public String listPullRequestFiles(
            @P("repository owner") String owner,
            @P("repository name") String repo,
            @P("PR number") Integer pullNumber
    ) {
        List<GithubChangedFile> files = githubClient.listPullRequestFiles(owner, repo, pullNumber);
        StringBuilder context = new StringBuilder();
        if (files != null) {
            int count = 0;
            for (GithubChangedFile file : files) {
                if (count >= MAX_FILES || context.length() >= MAX_CONTEXT_CHARS) {
                    break;
                }
                count++;
                appendLine(context, "- " + safeText(file.getFilename(), "N/A")
                        + " (" + safeText(file.getStatus(), "N/A")
                        + ", +" + defaultNumber(file.getAdditions())
                        + ", -" + defaultNumber(file.getDeletions())
                        + ", changes=" + defaultNumber(file.getChanges()) + ")");
                String patch = truncate(file.getPatch(), MAX_PATCH_CHARS_PER_FILE);
                if (StringUtils.hasText(patch)) {
                    appendLine(context, "  patch: " + patch.replace("\n", " "));
                }
            }
            if (files.size() > MAX_FILES) {
                appendLine(context, "- ... and " + (files.size() - MAX_FILES) + " more files");
            }
        }
        return truncate(context.toString(), MAX_CONTEXT_CHARS);
    }

    private void appendLine(StringBuilder builder, String line) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(line);
    }

    private String safeText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private int defaultNumber(Integer value) {
        return value == null ? 0 : value;
    }

    private String buildFallbackPrUrl(String owner, String repo, Integer pullNumber) {
        return "https://github.com/" + safeText(owner, "unknown") + "/" + safeText(repo, "unknown") + "/pull/" + pullNumber;
    }

    private String truncate(String content, int maxLength) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }
}
