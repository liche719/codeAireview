package com.codepilot.module.review.processor;

import com.codepilot.module.review.classifier.ReviewFileClassifier;
import com.codepilot.module.review.context.ReviewContext;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.planner.ReviewPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ReviewFilePrioritizer {

    public List<ReviewFile> prioritize(List<ReviewFile> reviewFiles, ReviewContext reviewContext) {
        if (reviewFiles == null || reviewFiles.size() <= 1 || reviewContext == null || reviewContext.reviewPlan() == null) {
            return reviewFiles == null ? List.of() : reviewFiles;
        }
        Map<String, Integer> priorityScores = priorityScores(reviewContext.reviewPlan());
        if (priorityScores.isEmpty()) {
            return reviewFiles;
        }
        Map<ReviewFile, Integer> originalIndex = new HashMap<>();
        for (int i = 0; i < reviewFiles.size(); i++) {
            originalIndex.put(reviewFiles.get(i), i);
        }
        List<ReviewFile> prioritizedFiles = reviewFiles.stream()
                .sorted(Comparator
                        .comparingInt((ReviewFile reviewFile) -> priorityScore(reviewFile, priorityScores))
                        .reversed()
                        .thenComparingInt(reviewFile -> originalIndex.getOrDefault(reviewFile, Integer.MAX_VALUE)))
                .toList();
        if (!sameOrder(reviewFiles, prioritizedFiles)) {
            log.info("Review files reordered by semantic review plan, before={}, after={}",
                    filePaths(reviewFiles), filePaths(prioritizedFiles));
        }
        return prioritizedFiles;
    }

    private Map<String, Integer> priorityScores(ReviewPlan reviewPlan) {
        Map<String, Integer> scores = new HashMap<>();
        if (reviewPlan.priorityFiles() == null) {
            return scores;
        }
        for (ReviewPlan.PriorityFile priorityFile : reviewPlan.priorityFiles()) {
            if (priorityFile != null) {
                scores.put(ReviewFileClassifier.normalizePath(priorityFile.filePath()), priorityFile.score());
            }
        }
        return scores;
    }

    private int priorityScore(ReviewFile reviewFile, Map<String, Integer> priorityScores) {
        if (reviewFile == null) {
            return Integer.MIN_VALUE;
        }
        return priorityScores.getOrDefault(ReviewFileClassifier.normalizePath(reviewFile.getFilePath()), Integer.MIN_VALUE);
    }

    private boolean sameOrder(List<ReviewFile> left, List<ReviewFile> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (left.get(i) != right.get(i)) {
                return false;
            }
        }
        return true;
    }

    private List<String> filePaths(List<ReviewFile> reviewFiles) {
        return reviewFiles.stream()
                .map(ReviewFile::getFilePath)
                .toList();
    }
}
