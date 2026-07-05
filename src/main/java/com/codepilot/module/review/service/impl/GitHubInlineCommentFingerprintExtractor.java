package com.codepilot.module.review.service.impl;

import com.codepilot.module.git.dto.GithubIssueComment;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GitHubInlineCommentFingerprintExtractor {

    private static final Pattern INLINE_FINGERPRINT_PATTERN =
            Pattern.compile("<!--\\s*codepilot-inline-review:([a-f0-9]{16,64})\\s*-->", Pattern.CASE_INSENSITIVE);

    Set<String> extract(List<GithubIssueComment> comments) {
        Set<String> fingerprints = new HashSet<>();
        for (GithubIssueComment comment : comments) {
            if (comment == null || !StringUtils.hasText(comment.getBody())) {
                continue;
            }
            Matcher matcher = INLINE_FINGERPRINT_PATTERN.matcher(comment.getBody());
            while (matcher.find()) {
                fingerprints.add(matcher.group(1).toLowerCase());
            }
        }
        return fingerprints;
    }
}
