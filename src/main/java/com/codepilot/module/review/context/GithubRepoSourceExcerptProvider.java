package com.codepilot.module.review.context;

import com.codepilot.module.git.client.GithubClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GithubRepoSourceExcerptProvider implements RepoSourceExcerptProvider {

    private final GithubClient githubClient;

    @Override
    public String getFileContent(String owner, String repo, String path, String ref) {
        return githubClient.getFileContent(owner, repo, path, ref);
    }
}
