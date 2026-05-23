package com.codepilot.module.review.processor;

import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.review.assembler.ReviewIssueAssembler;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ReviewFileReviewer {

    private final AiReviewService aiReviewService;

    private final ReviewIssueAssembler reviewIssueAssembler;

    public List<ReviewIssue> review(Long taskId, List<ReviewFile> reviewFiles) {
        List<String> allChangedFiles = changedFilePaths(reviewFiles);
        List<ReviewIssue> reviewIssues = new ArrayList<>();
        for (ReviewFile reviewFile : reviewFiles) {
            if (!Boolean.TRUE.equals(reviewFile.getSkipped())) {
                reviewIssues.addAll(reviewFileWithAi(taskId, reviewFile, allChangedFiles));
            }
        }
        return reviewIssues;
    }

    private List<String> changedFilePaths(List<ReviewFile> reviewFiles) {
        return reviewFiles.stream()
                .map(ReviewFile::getFilePath)
                .filter(StringUtils::hasText)
                .toList();
    }

    private List<ReviewIssue> reviewFileWithAi(Long taskId, ReviewFile reviewFile, List<String> allChangedFiles) {
        try {
            AiReviewResult aiReviewResult = aiReviewService.reviewFile(
                    taskId,
                    reviewFile.getFilePath(),
                    reviewFile.getPatch(),
                    allChangedFiles
            );
            return reviewIssueAssembler.toReviewIssues(taskId, reviewFile.getFilePath(), aiReviewResult);
        } catch (Exception exception) {
            throw new IllegalStateException("AI review failed for file " + reviewFile.getFilePath(), exception);
        }
    }
}
