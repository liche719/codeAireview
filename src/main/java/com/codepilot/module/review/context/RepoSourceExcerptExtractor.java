package com.codepilot.module.review.context;

import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class RepoSourceExcerptExtractor {

    private final RepoSourceCandidateCollector candidateCollector = new RepoSourceCandidateCollector();

    private final RepoSourceExcerptFetcher excerptFetcher;

    RepoSourceExcerptExtractor() {
        this(null, new ReviewProperties());
    }

    @Autowired
    public RepoSourceExcerptExtractor(
            RepoSourceExcerptProvider repoSourceExcerptProvider,
            ReviewProperties reviewProperties
    ) {
        ReviewProperties safeProperties = reviewProperties == null ? new ReviewProperties() : reviewProperties;
        this.excerptFetcher = new RepoSourceExcerptFetcher(repoSourceExcerptProvider, safeProperties);
    }

    public List<ReviewContext.RepoSourceExcerpt> repoSourceExcerpts(
            ReviewTask task,
            List<ReviewFile> reviewFiles,
            List<ReviewContext.SemanticFileContext> semanticFileContexts,
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints
    ) {
        if (!canFetch(task) || reviewFiles == null || reviewFiles.isEmpty() || !excerptFetcher.canFetch()) {
            return List.of();
        }

        Map<String, ReviewFile> changedFilesByPath = changedFilesByPath(reviewFiles);
        if (changedFilesByPath.isEmpty()) {
            return List.of();
        }

        Map<String, ReviewContext.SemanticFileContext> semanticByPath = semanticContextByPath(semanticFileContexts);
        Map<String, ReviewContext.RepoSourceExcerpt> excerpts = new LinkedHashMap<>();
        Map<String, RepoSourceTextExcerpt> contentCache = new LinkedHashMap<>();
        Set<String> fetchAttempts = new LinkedHashSet<>();
        int maxFetchAttempts = Math.max(excerptFetcher.maxRepoContextFiles(), 1) * 4;

        for (RepoSourceCandidate candidate : candidateCollector.candidates(reviewFiles, semanticByPath, repoRelationshipHints)) {
            if (excerpts.size() >= excerptFetcher.maxRepoContextFiles() || fetchAttempts.size() >= maxFetchAttempts) {
                break;
            }
            addExcerpt(task, candidate, changedFilesByPath, contentCache, fetchAttempts, excerpts);
        }
        return List.copyOf(excerpts.values());
    }

    private boolean canFetch(ReviewTask task) {
        return task != null
                && StringUtils.hasText(task.getRepoOwner())
                && StringUtils.hasText(task.getRepoName())
                && StringUtils.hasText(task.getHeadSha());
    }

    private Map<String, ReviewFile> changedFilesByPath(List<ReviewFile> reviewFiles) {
        Map<String, ReviewFile> filesByPath = new LinkedHashMap<>();
        reviewFiles.stream()
                .filter(reviewFile -> reviewFile != null && !Boolean.TRUE.equals(reviewFile.getSkipped()))
                .filter(reviewFile -> StringUtils.hasText(reviewFile.getFilePath()))
                .filter(reviewFile -> RepoSourcePathUtils.isSupportedSourcePath(reviewFile.getFilePath()))
                .forEach(reviewFile -> filesByPath.putIfAbsent(
                        RepoSourcePathUtils.normalizePath(reviewFile.getFilePath()),
                        reviewFile
                ));
        return filesByPath;
    }

    private Map<String, ReviewContext.SemanticFileContext> semanticContextByPath(
            List<ReviewContext.SemanticFileContext> semanticFileContexts
    ) {
        if (semanticFileContexts == null || semanticFileContexts.isEmpty()) {
            return Map.of();
        }
        Map<String, ReviewContext.SemanticFileContext> contextsByPath = new LinkedHashMap<>();
        semanticFileContexts.stream()
                .filter(context -> context != null && StringUtils.hasText(context.filePath()))
                .forEach(context -> contextsByPath.putIfAbsent(
                        RepoSourcePathUtils.normalizePath(context.filePath()),
                        context
                ));
        return contextsByPath;
    }

    private void addExcerpt(
            ReviewTask task,
            RepoSourceCandidate candidate,
            Map<String, ReviewFile> changedFilesByPath,
            Map<String, RepoSourceTextExcerpt> contentCache,
            Set<String> fetchAttempts,
            Map<String, ReviewContext.RepoSourceExcerpt> excerpts
    ) {
        if (excerpts.size() >= excerptFetcher.maxRepoContextFiles()) {
            return;
        }
        ReviewFile changedRelatedFile = changedFilesByPath.get(RepoSourcePathUtils.normalizePath(candidate.relatedFile()));
        if (changedRelatedFile != null && !StringUtils.hasText(changedRelatedFile.getPatch())) {
            return;
        }
        RepoSourceTextExcerpt excerpt = excerptFetcher.sourceExcerpt(task, candidate, contentCache, fetchAttempts);
        if (!StringUtils.hasText(excerpt.text())) {
            return;
        }
        excerpts.putIfAbsent(
                RepoSourcePathUtils.normalizePath(candidate.sourceFile()) + "\u0000"
                        + RepoSourcePathUtils.normalizePath(candidate.relatedFile()),
                new ReviewContext.RepoSourceExcerpt(
                        candidate.sourceFile(),
                        candidate.relatedFile(),
                        candidate.reason(),
                        excerpt.text(),
                        excerpt.truncated()
                )
        );
    }
}
