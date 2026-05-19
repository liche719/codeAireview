package com.codepilot.module.review.service;

public interface GitHubInlineCommentService {

    GitHubInlineCommentResult commentInlineIssues(Long taskId);
}
