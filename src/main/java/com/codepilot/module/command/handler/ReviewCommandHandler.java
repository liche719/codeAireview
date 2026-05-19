package com.codepilot.module.command.handler;

import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.dto.GithubCommandType;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.service.ReviewTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReviewCommandHandler implements GithubCommandHandler {

    private final ReviewTaskService reviewTaskService;

    @Override
    public GithubCommandType commandType() {
        return GithubCommandType.REVIEW;
    }

    @Override
    public GithubCommandHandleResult handle(GitHubPullRequestWebhookPayload payload) {
        ReviewCreateResponse response = reviewTaskService.createTask(
                payload.getPrUrl(),
                payload.getTitle(),
                ReviewCommentMode.SUMMARY_ONLY
        );
        return GithubCommandHandleResult.processed(response.getTaskId(), payload.getAction());
    }
}
