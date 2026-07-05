package com.codepilot.module.review.service.impl;

import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class GitHubInlineCommentFingerprintBuilder {

    String issueKey(ReviewIssue issue) {
        return nullToDash(issue.getFilePath())
                + ":"
                + issue.getLineNumber()
                + ":"
                + nullToDash(issue.getIssueType());
    }

    String issueFingerprint(ReviewTask task, String headSha, ReviewIssue issue) {
        String rawFingerprint = nullToDash(task.getRepoOwner())
                + "/"
                + nullToDash(task.getRepoName())
                + ":"
                + task.getPrNumber()
                + ":"
                + nullToDash(headSha)
                + ":"
                + issueKey(issue);
        return sha256Hex(rawFingerprint);
    }

    private String nullToDash(String content) {
        return StringUtils.hasText(content) ? content : "N/A";
    }

    private String sha256Hex(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
