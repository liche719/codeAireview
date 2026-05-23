package com.codepilot.module.review.sync;

import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.state.ReviewTaskStateManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class ReviewTaskHeadShaRefresher {

    private final GithubClient githubClient;

    private final ReviewTaskStateManager reviewTaskStateManager;

    public void refresh(ReviewTask task) {
        GithubPullRequestDetail detail = githubClient.getPullRequestDetail(
                task.getRepoOwner(),
                task.getRepoName(),
                task.getPrNumber()
        );
        if (!StringUtils.hasText(detail.getHeadSha())) {
            return;
        }
        String headSha = detail.getHeadSha().trim();
        if (headSha.equals(task.getHeadSha())) {
            return;
        }
        reviewTaskStateManager.updateHeadSha(task, headSha);
    }
}
