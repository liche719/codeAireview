package com.codepilot.module.command.handler;

import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.dto.GithubCommandType;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import com.codepilot.module.review.report.ReviewReportFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UnavailableCommandHandler implements GithubCommandHandler {

    private final GithubClient githubClient;

    @Override
    public GithubCommandType commandType() {
        return GithubCommandType.UNAVAILABLE;
    }

    @Override
    public GithubCommandHandleResult handle(GitHubPullRequestWebhookPayload payload) {
        try {
            githubClient.createPullRequestComment(
                    payload.getOwner(),
                    payload.getRepo(),
                    payload.getPullNumber(),
                    """
                    %s

                    **CodePilot AI** is temporarily unavailable.

                    The current command needs the LLM service, but the model is not configured or the LLM call failed. Please check `CODEPILOT_LLM_API_KEY`, `CODEPILOT_LLM_BASE_URL`, and `CODEPILOT_LLM_MODEL`, then try again.
                    """.formatted(ReviewReportFormatter.DEFAULT_COMMENT_MARKER)
            );
        } catch (Exception exception) {
            log.warn("GitHub unavailable command comment failed but ignored, owner={}, repo={}, pullNumber={}, message={}",
                    payload.getOwner(), payload.getRepo(), payload.getPullNumber(), exception.getMessage());
        }
        return GithubCommandHandleResult.processed(null, payload.getAction());
    }
}
