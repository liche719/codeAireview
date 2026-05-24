package com.codepilot.module.review.context;

import com.codepilot.module.review.entity.ReviewFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class ReviewContextBuilder {

    private final ReviewContextSignalExtractor reviewContextSignalExtractor;

    private final ReviewContextRelationshipExtractor reviewContextRelationshipExtractor;

    ReviewContextBuilder() {
        this(new ReviewContextSignalExtractor(), new ReviewContextRelationshipExtractor());
    }

    @Autowired
    public ReviewContextBuilder(
            ReviewContextSignalExtractor reviewContextSignalExtractor,
            ReviewContextRelationshipExtractor reviewContextRelationshipExtractor
    ) {
        this.reviewContextSignalExtractor = reviewContextSignalExtractor;
        this.reviewContextRelationshipExtractor = reviewContextRelationshipExtractor;
    }

    public ReviewContext build(List<ReviewFile> reviewFiles) {
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

        return new ReviewContext(
                allChangedFiles,
                reviewFiles.size(),
                (int) reviewFiles.stream().filter(reviewFile -> !Boolean.TRUE.equals(reviewFile.getSkipped())).count(),
                (int) reviewFiles.stream().filter(reviewFile -> Boolean.TRUE.equals(reviewFile.getSkipped())).count(),
                sumAdditions(reviewFiles),
                sumDeletions(reviewFiles),
                sumPatchChars(reviewFiles),
                skippedFiles,
                reviewContextSignalExtractor.fileSummaries(reviewFiles),
                semanticFileContexts,
                reviewContextRelationshipExtractor.repoRelationshipHints(reviewFiles, semanticFileContexts),
                reviewContextSignalExtractor.reviewSignals(reviewFiles)
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
