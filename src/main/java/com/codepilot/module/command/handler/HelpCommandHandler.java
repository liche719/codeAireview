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
public class HelpCommandHandler implements GithubCommandHandler {

    private final GithubClient githubClient;

    @Override
    public GithubCommandType commandType() {
        return GithubCommandType.HELP;
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

                    **CodePilot AI** supports these PR commands:

                    - `@x-pilotx review`
                    - `@x-pilotx fix dry-run`
                    - `@x-pilotx fix`

                    `fix` uses the latest review findings, generates a small unified diff, validates it, and only then pushes a new commit to this PR branch.
                    """.formatted(ReviewReportFormatter.DEFAULT_COMMENT_MARKER)
            );
        } catch (Exception exception) {
            log.warn("GitHub command help comment failed but ignored, owner={}, repo={}, pullNumber={}, message={}",
                    payload.getOwner(), payload.getRepo(), payload.getPullNumber(), exception.getMessage());
        }
        return GithubCommandHandleResult.processed(null, payload.getAction());
    }
}
