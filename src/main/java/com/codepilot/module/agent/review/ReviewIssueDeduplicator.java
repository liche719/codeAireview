package com.codepilot.module.agent.review;

import com.codepilot.module.agent.dto.AiReviewIssue;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class ReviewIssueDeduplicator {

    public List<AiReviewIssue> dedupe(List<AiReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        List<AiReviewIssue> deduped = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AiReviewIssue issue : issues) {
            if (issue == null) {
                continue;
            }
            String key = nullToDash(issue.getFilePath())
                    + ":"
                    + nullToDash(issue.getIssueType())
                    + ":"
                    + nullToDash(issue.getSeverity())
                    + ":"
                    + nullToDash(issue.getTitle())
                    + ":"
                    + nullToDash(issue.getDescription());
            if (seen.add(key)) {
                deduped.add(issue);
            }
        }
        return deduped;
    }

    private String nullToDash(String content) {
        return StringUtils.hasText(content) ? content : "N/A";
    }
}
