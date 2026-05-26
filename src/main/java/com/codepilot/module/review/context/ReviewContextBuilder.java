package com.codepilot.module.review.context;

import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.planner.ReviewPlan;
import com.codepilot.module.review.planner.SemanticReviewPlanner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class ReviewContextBuilder {

    private final ReviewContextSignalExtractor reviewContextSignalExtractor;

    private final ReviewContextRelationshipExtractor reviewContextRelationshipExtractor;

    private final ReviewImpactPlanner reviewImpactPlanner;

    private final ReviewRelatedPatchExtractor reviewRelatedPatchExtractor;

    private final RepoSourceExcerptExtractor repoSourceExcerptExtractor;

    private final SemanticReviewPlanner semanticReviewPlanner;

    private final ReviewLinkedIssueContextProvider reviewLinkedIssueContextProvider;

    ReviewContextBuilder() {
        this(
                new ReviewContextSignalExtractor(),
                new ReviewContextRelationshipExtractor(),
                new ReviewImpactPlanner(),
                new ReviewRelatedPatchExtractor(),
                new RepoSourceExcerptExtractor(),
                new SemanticReviewPlanner(),
                new ReviewLinkedIssueContextProvider()
        );
    }

    @Autowired
    public ReviewContextBuilder(
            ReviewContextSignalExtractor reviewContextSignalExtractor,
            ReviewContextRelationshipExtractor reviewContextRelationshipExtractor,
            ReviewImpactPlanner reviewImpactPlanner,
            ReviewRelatedPatchExtractor reviewRelatedPatchExtractor,
            RepoSourceExcerptExtractor repoSourceExcerptExtractor,
            SemanticReviewPlanner semanticReviewPlanner,
            ReviewLinkedIssueContextProvider reviewLinkedIssueContextProvider
    ) {
        this.reviewContextSignalExtractor = reviewContextSignalExtractor;
        this.reviewContextRelationshipExtractor = reviewContextRelationshipExtractor;
        this.reviewImpactPlanner = reviewImpactPlanner;
        this.reviewRelatedPatchExtractor = reviewRelatedPatchExtractor;
        this.repoSourceExcerptExtractor = repoSourceExcerptExtractor;
        this.semanticReviewPlanner = semanticReviewPlanner;
        this.reviewLinkedIssueContextProvider = reviewLinkedIssueContextProvider;
    }

    public ReviewContextBuilder(
            ReviewContextSignalExtractor reviewContextSignalExtractor,
            ReviewContextRelationshipExtractor reviewContextRelationshipExtractor
    ) {
        this(
                reviewContextSignalExtractor,
                reviewContextRelationshipExtractor,
                new ReviewImpactPlanner(),
                new ReviewRelatedPatchExtractor(),
                new RepoSourceExcerptExtractor(),
                new SemanticReviewPlanner(),
                new ReviewLinkedIssueContextProvider()
        );
    }

    public ReviewContextBuilder(
            ReviewContextSignalExtractor reviewContextSignalExtractor,
            ReviewContextRelationshipExtractor reviewContextRelationshipExtractor,
            ReviewImpactPlanner reviewImpactPlanner
    ) {
        this(
                reviewContextSignalExtractor,
                reviewContextRelationshipExtractor,
                reviewImpactPlanner,
                new ReviewRelatedPatchExtractor(),
                new RepoSourceExcerptExtractor(),
                new SemanticReviewPlanner(),
                new ReviewLinkedIssueContextProvider()
        );
    }

    public ReviewContextBuilder(
            ReviewContextSignalExtractor reviewContextSignalExtractor,
            ReviewContextRelationshipExtractor reviewContextRelationshipExtractor,
            ReviewImpactPlanner reviewImpactPlanner,
            ReviewRelatedPatchExtractor reviewRelatedPatchExtractor
    ) {
        this(
                reviewContextSignalExtractor,
                reviewContextRelationshipExtractor,
                reviewImpactPlanner,
                reviewRelatedPatchExtractor,
                new RepoSourceExcerptExtractor(),
                new SemanticReviewPlanner(),
                new ReviewLinkedIssueContextProvider()
        );
    }

    public ReviewContextBuilder(
            ReviewContextSignalExtractor reviewContextSignalExtractor,
            ReviewContextRelationshipExtractor reviewContextRelationshipExtractor,
            ReviewImpactPlanner reviewImpactPlanner,
            ReviewRelatedPatchExtractor reviewRelatedPatchExtractor,
            RepoSourceExcerptExtractor repoSourceExcerptExtractor
    ) {
        this(
                reviewContextSignalExtractor,
                reviewContextRelationshipExtractor,
                reviewImpactPlanner,
                reviewRelatedPatchExtractor,
                repoSourceExcerptExtractor,
                new SemanticReviewPlanner(),
                new ReviewLinkedIssueContextProvider()
        );
    }

    public ReviewContextBuilder(
            ReviewContextSignalExtractor reviewContextSignalExtractor,
            ReviewContextRelationshipExtractor reviewContextRelationshipExtractor,
            ReviewImpactPlanner reviewImpactPlanner,
            ReviewRelatedPatchExtractor reviewRelatedPatchExtractor,
            RepoSourceExcerptExtractor repoSourceExcerptExtractor,
            SemanticReviewPlanner semanticReviewPlanner
    ) {
        this(
                reviewContextSignalExtractor,
                reviewContextRelationshipExtractor,
                reviewImpactPlanner,
                reviewRelatedPatchExtractor,
                repoSourceExcerptExtractor,
                semanticReviewPlanner,
                new ReviewLinkedIssueContextProvider()
        );
    }

    public ReviewContext build(List<ReviewFile> reviewFiles) {
        return build(null, reviewFiles);
    }

    public ReviewContext build(ReviewTask task, List<ReviewFile> reviewFiles) {
        if (reviewFiles == null || reviewFiles.isEmpty()) {
            return ReviewContext.empty();
        }
        List<String> allChangedFiles = reviewFiles.stream()
                .map(ReviewFile::getFilePath)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        List<ReviewContext.SkippedFile> skippedFiles = reviewFiles.stream()
                .filter(reviewFile -> Boolean.TRUE.equals(reviewFile.getSkipped()))
                .filter(reviewFile -> StringUtils.hasText(reviewFile.getFilePath()))
                .map(reviewFile -> new ReviewContext.SkippedFile(
                        reviewFile.getFilePath().trim(),
                        StringUtils.hasText(reviewFile.getSkipReason()) ? reviewFile.getSkipReason().trim() : "skipped"
                ))
                .toList();
        List<ReviewContext.SemanticFileContext> semanticFileContexts =
                reviewContextSignalExtractor.semanticFileContexts(reviewFiles);
        List<ReviewContext.FileSummary> fileSummaries = reviewContextSignalExtractor.fileSummaries(reviewFiles);
        List<ReviewContext.RepoRelationshipHint> repoRelationshipHints =
                reviewContextRelationshipExtractor.repoRelationshipHints(reviewFiles, semanticFileContexts);
        List<ReviewContext.ReviewSignal> reviewSignals = reviewContextSignalExtractor.reviewSignals(reviewFiles);
        ReviewContext.ReviewImpactPlan reviewImpactPlan =
                reviewImpactPlanner.plan(fileSummaries, semanticFileContexts, repoRelationshipHints, reviewSignals);
        List<ReviewContext.RelatedPatchExcerpt> relatedPatchExcerpts =
                reviewRelatedPatchExtractor.relatedPatchExcerpts(reviewFiles, repoRelationshipHints);
        List<ReviewContext.RepoSourceExcerpt> repoSourceExcerpts =
                repoSourceExcerptExtractor.repoSourceExcerpts(task, reviewFiles, semanticFileContexts, repoRelationshipHints);
        List<ReviewContext.LinkedIssueContext> linkedIssueContexts =
                reviewLinkedIssueContextProvider.linkedIssues(task);
        ReviewPlan reviewPlan = semanticReviewPlanner.plan(
                reviewFiles,
                fileSummaries,
                semanticFileContexts,
                repoRelationshipHints,
                reviewImpactPlan,
                relatedPatchExcerpts,
                repoSourceExcerpts,
                reviewSignals,
                linkedIssueContexts
        );

        return new ReviewContext(
                allChangedFiles,
                reviewFiles.size(),
                (int) reviewFiles.stream().filter(reviewFile -> !Boolean.TRUE.equals(reviewFile.getSkipped())).count(),
                (int) reviewFiles.stream().filter(reviewFile -> Boolean.TRUE.equals(reviewFile.getSkipped())).count(),
                sumAdditions(reviewFiles),
                sumDeletions(reviewFiles),
                sumPatchChars(reviewFiles),
                skippedFiles,
                fileSummaries,
                semanticFileContexts,
                repoRelationshipHints,
                reviewImpactPlan,
                reviewPlan,
                linkedIssueContexts,
                relatedPatchExcerpts,
                repoSourceExcerpts,
                reviewSignals
        );
    }

    private int sumAdditions(List<ReviewFile> reviewFiles) {
        return reviewFiles.stream()
                .map(ReviewFile::getAdditions)
                .mapToInt(this::valueOrZero)
                .sum();
    }

    private int sumDeletions(List<ReviewFile> reviewFiles) {
        return reviewFiles.stream()
                .map(ReviewFile::getDeletions)
                .mapToInt(this::valueOrZero)
                .sum();
    }

    private int sumPatchChars(List<ReviewFile> reviewFiles) {
        return reviewFiles.stream()
                .map(ReviewFile::getPatch)
                .filter(StringUtils::hasText)
                .mapToInt(String::length)
                .sum();
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
