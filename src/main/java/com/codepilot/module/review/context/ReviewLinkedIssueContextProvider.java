package com.codepilot.module.review.context;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubLinkedIssue;
import com.codepilot.module.review.entity.ReviewTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Component
public class ReviewLinkedIssueContextProvider {

    private final GithubClient githubClient;

    public ReviewLinkedIssueContextProvider() {
        this.githubClient = null;
    }

    @Autowired
    public ReviewLinkedIssueContextProvider(GithubClient githubClient) {
        this.githubClient = githubClient;
    }

    public List<ReviewContext.LinkedIssueContext> linkedIssues(ReviewTask task) {
        if (githubClient == null || task == null
                || !StringUtils.hasText(task.getRepoOwner())
                || !StringUtils.hasText(task.getRepoName())
                || task.getPrNumber() == null) {
            return List.of();
        }
        try {
            return githubClient.listPullRequestLinkedIssues(
                            task.getRepoOwner(),
                            task.getRepoName(),
                            task.getPrNumber()
                    )
                    .stream()
                    .map(this::toContext)
                    .toList();
        } catch (Exception exception) {
            log.warn("GitHub linked issue context unavailable, taskId={}, owner={}, repo={}, pullNumber={}, message={}",
                    task.getId(),
                    task.getRepoOwner(),
                    task.getRepoName(),
                    task.getPrNumber(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
            return List.of();
        }
    }

    private ReviewContext.LinkedIssueContext toContext(GithubLinkedIssue issue) {
        if (issue == null) {
            return null;
        }
        return new ReviewContext.LinkedIssueContext(
                issue.getRepositoryOwner(),
                issue.getRepositoryName(),
                issue.getNumber(),
                issue.getTitle(),
                issue.getState(),
                issue.getHtmlUrl(),
                issue.getLinkSource()
        );
    }
}
