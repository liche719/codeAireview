package com.codepilot.module.command.handler;

import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.dto.GithubCommandType;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.service.ReviewTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewCommandHandler implements GithubCommandHandler {

    private final ReviewTaskService reviewTaskService;

    private final GithubClient githubClient;

    @Override
    public GithubCommandType commandType() {
        return GithubCommandType.REVIEW;
    }

    @Override
    public GithubCommandHandleResult handle(GitHubPullRequestWebhookPayload payload) {
        ReviewCreateResponse response = reviewTaskService.createTask(
                payload.getPrUrl(),
                payload.getTitle(),
                ReviewCommentMode.SUMMARY_ONLY,
                resolveHeadSha(payload)
        );
        return GithubCommandHandleResult.processed(response.getTaskId(), payload.getAction());
    }

    private String resolveHeadSha(GitHubPullRequestWebhookPayload payload) {
        if (payload == null) {
            return null;
        }
        if (StringUtils.hasText(payload.getHeadSha())) {
            return payload.getHeadSha().trim();
        }
        if (!StringUtils.hasText(payload.getOwner())
                || !StringUtils.hasText(payload.getRepo())
                || payload.getPullNumber() == null) {
            return null;
        }
        try {
            GithubPullRequestDetail detail = githubClient.getPullRequestDetail(
                    payload.getOwner(),
                    payload.getRepo(),
                    payload.getPullNumber()
            );
            return StringUtils.hasText(detail.getHeadSha()) ? detail.getHeadSha().trim() : null;
        } catch (Exception exception) {
            log.warn("Failed to resolve PR head sha for review command, owner={}, repo={}, pullNumber={}, errorType={}, message={}",
                    payload.getOwner(),
                    payload.getRepo(),
                    payload.getPullNumber(),
                    exception.getClass().getSimpleName(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
            return null;
        }
    }
}
