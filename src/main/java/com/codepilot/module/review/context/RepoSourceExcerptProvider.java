package com.codepilot.module.review.context;

public interface RepoSourceExcerptProvider {

    String getFileContent(String owner, String repo, String path, String ref);
}
