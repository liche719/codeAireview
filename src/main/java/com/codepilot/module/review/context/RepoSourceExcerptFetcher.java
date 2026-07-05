package com.codepilot.module.review.context;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Set;

@Slf4j
class RepoSourceExcerptFetcher {

    private static final int DEFAULT_MAX_REPO_CONTEXT_FILES = 6;

    private static final int DEFAULT_MAX_REPO_CONTEXT_FILE_CHARS = 20000;

    private static final int DEFAULT_MAX_REPO_CONTEXT_EXCERPT_CHARS = 900;

    private final RepoSourceExcerptProvider repoSourceExcerptProvider;

    private final ReviewProperties reviewProperties;

    RepoSourceExcerptFetcher(
            RepoSourceExcerptProvider repoSourceExcerptProvider,
            ReviewProperties reviewProperties
    ) {
        this.repoSourceExcerptProvider = repoSourceExcerptProvider;
        this.reviewProperties = reviewProperties == null ? new ReviewProperties() : reviewProperties;
    }

    boolean canFetch() {
        return repoSourceExcerptProvider != null;
    }

    int maxRepoContextFiles() {
        int configured = reviewProperties.getMaxRepoContextFiles();
        return configured < 0 ? DEFAULT_MAX_REPO_CONTEXT_FILES : configured;
    }

    RepoSourceTextExcerpt sourceExcerpt(
            ReviewTask task,
            RepoSourceCandidate candidate,
            Map<String, RepoSourceTextExcerpt> contentCache,
            Set<String> fetchAttempts
    ) {
        String cacheKey = RepoSourcePathUtils.normalizePath(candidate.relatedFile());
        if (contentCache.containsKey(cacheKey)) {
            return contentCache.get(cacheKey);
        }
        if (!fetchAttempts.add(cacheKey)) {
            return new RepoSourceTextExcerpt("", false);
        }
        try {
            String content = repoSourceExcerptProvider.getFileContent(
                    task.getRepoOwner(),
                    task.getRepoName(),
                    candidate.relatedFile(),
                    task.getHeadSha()
            );
            RepoSourceTextExcerpt excerpt = excerpt(content);
            contentCache.put(cacheKey, excerpt);
            return excerpt;
        } catch (Exception exception) {
            log.debug("Skip repository source excerpt because GitHub content fetch failed, owner={}, repo={}, path={}, ref={}, errorType={}",
                    task.getRepoOwner(),
                    task.getRepoName(),
                    candidate.relatedFile(),
                    task.getHeadSha(),
                    exception.getClass().getSimpleName());
            return new RepoSourceTextExcerpt("", false);
        }
    }

    private RepoSourceTextExcerpt excerpt(String content) {
        String sanitized = SensitiveDataSanitizer.redact(content);
        if (!StringUtils.hasText(sanitized)) {
            return new RepoSourceTextExcerpt("", false);
        }

        int maxFileChars = maxRepoContextFileChars();
        boolean truncated = maxFileChars > 0 && sanitized.length() > maxFileChars;
        String bounded = truncated ? sanitized.substring(0, maxFileChars) : sanitized;

        int maxExcerptChars = maxRepoContextExcerptChars();
        if (maxExcerptChars > 0 && bounded.length() > maxExcerptChars) {
            return new RepoSourceTextExcerpt(bounded.substring(0, maxExcerptChars).stripTrailing(), true);
        }
        return new RepoSourceTextExcerpt(bounded.stripTrailing(), truncated);
    }

    private int maxRepoContextFileChars() {
        int configured = reviewProperties.getMaxRepoContextFileChars();
        return configured < 0 ? DEFAULT_MAX_REPO_CONTEXT_FILE_CHARS : configured;
    }

    private int maxRepoContextExcerptChars() {
        int configured = reviewProperties.getMaxRepoContextExcerptChars();
        return configured < 0 ? DEFAULT_MAX_REPO_CONTEXT_EXCERPT_CHARS : configured;
    }
}
