package com.codepilot.module.review.processor;

import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.dto.AiReviewRequest;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.review.assembler.ReviewIssueAssembler;
import com.codepilot.module.review.classifier.ReviewFileClassifier;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.context.ReviewContext;
import com.codepilot.module.review.context.ReviewContextBuilder;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.planner.ReviewPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewFileReviewer {

    private final AiReviewService aiReviewService;

    private final ReviewIssueAssembler reviewIssueAssembler;

    private final ReviewIssueLocationGuard reviewIssueLocationGuard;

    private final ReviewIssuePatchVerifier reviewIssuePatchVerifier;

    private final ReviewContextBuilder reviewContextBuilder;

    private final ReviewFindingRanker reviewFindingRanker;

    private final ReviewProperties reviewProperties;

    public List<ReviewIssue> review(Long taskId, List<ReviewFile> reviewFiles) {
        return review(task(taskId), reviewFiles);
    }

    public List<ReviewIssue> review(ReviewTask task, List<ReviewFile> reviewFiles) {
        if (reviewFiles == null || reviewFiles.isEmpty()) {
            return List.of();
        }
        Long taskId = task == null ? null : task.getId();
        ReviewContext reviewContext = reviewContextBuilder.build(task, reviewFiles);
        List<ReviewFile> reviewableFiles = reviewFiles.stream()
                .filter(reviewFile -> !Boolean.TRUE.equals(reviewFile.getSkipped()))
                .toList();
        List<ReviewFile> plannedReviewFiles = prioritizeReviewFiles(reviewableFiles, reviewContext);
        List<ReviewIssue> reviewIssues = new ArrayList<>();
        int failedFileCount = 0;
        Exception firstFailure = null;
        for (FileReviewOutcome outcome : reviewFiles(taskId, plannedReviewFiles, reviewContext)) {
            if (outcome.failure() != null) {
                failedFileCount++;
                if (firstFailure == null) {
                    firstFailure = outcome.failure();
                }
            }
            reviewIssues.addAll(outcome.issues());
        }
        reviewIssues = reviewFindingRanker.rank(reviewIssues);
        if (!reviewableFiles.isEmpty() && failedFileCount == reviewableFiles.size()) {
            throw new IllegalStateException("AI review failed for all reviewable files, failedCount=" + failedFileCount
                    + ", firstError=" + failureMessage(firstFailure),
                    firstFailure);
        }
        return reviewIssues;
    }

    private List<ReviewFile> prioritizeReviewFiles(List<ReviewFile> reviewFiles, ReviewContext reviewContext) {
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

    private ReviewTask task(Long taskId) {
        ReviewTask task = new ReviewTask();
        task.setId(taskId);
        return task;
    }

    private List<FileReviewOutcome> reviewFiles(
            Long taskId,
            List<ReviewFile> reviewFiles,
            ReviewContext reviewContext
    ) {
        int parallelism = parallelism(reviewFiles.size());
        if (parallelism <= 1 || reviewFiles.size() <= 1) {
            return reviewFiles.stream()
                    .map(reviewFile -> reviewFileSafely(taskId, reviewFile, reviewContext))
                    .toList();
        }

        ExecutorService executorService = Executors.newFixedThreadPool(parallelism, threadFactory(taskId));
        try {
            List<CompletableFuture<FileReviewOutcome>> futures = reviewFiles.stream()
                    .map(reviewFile -> CompletableFuture.supplyAsync(
                            () -> reviewFileSafely(taskId, reviewFile, reviewContext),
                            executorService
                    ))
                    .toList();
            return futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
        } finally {
            executorService.shutdown();
        }
    }

    private FileReviewOutcome reviewFileSafely(Long taskId, ReviewFile reviewFile, ReviewContext reviewContext) {
        try {
            return new FileReviewOutcome(reviewFileWithAi(taskId, reviewFile, reviewContext), null);
        } catch (Exception exception) {
            log.warn("AI review failed for file but review task will continue, taskId={}, filePath={}, errorType={}, message={}",
                    taskId,
                    reviewFile.getFilePath(),
                    exception.getClass().getSimpleName(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
            return new FileReviewOutcome(List.of(failureIssue(taskId, reviewFile, exception)), exception);
        }
    }

    private int parallelism(int reviewableFileCount) {
        int configuredParallelism = reviewProperties == null ? 1 : reviewProperties.getMaxParallelFiles();
        return Math.min(Math.max(1, configuredParallelism), Math.max(1, reviewableFileCount));
    }

    private ThreadFactory threadFactory(Long taskId) {
        AtomicInteger index = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("codepilot-review-file-" + (taskId == null ? "unknown" : taskId) + "-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private List<ReviewIssue> reviewFileWithAi(Long taskId, ReviewFile reviewFile, ReviewContext reviewContext) {
        try {
            AiReviewResult aiReviewResult = aiReviewService.reviewFile(new AiReviewRequest(
                    taskId,
                    reviewFile.getFilePath(),
                    reviewFile.getPatch(),
                    reviewContext.toAiReviewContext()
            ));
            List<ReviewIssue> reviewIssues = reviewIssueAssembler.toReviewIssues(
                    taskId,
                    reviewFile.getFilePath(),
                    aiReviewResult
            );
            reviewIssues = reviewIssuePatchVerifier.keepVerified(
                    reviewFile.getFilePath(),
                    reviewFile.getPatch(),
                    reviewContext,
                    reviewIssues
            );
            return reviewIssueLocationGuard.keepOnlyCommentableChangedLines(
                    reviewFile.getFilePath(),
                    reviewFile.getPatch(),
                    reviewIssues
            );
        } catch (Exception exception) {
            throw new IllegalStateException("AI review failed for file " + reviewFile.getFilePath(), exception);
        }
    }

    private ReviewIssue failureIssue(Long taskId, ReviewFile reviewFile, Exception exception) {
        ReviewIssue issue = new ReviewIssue();
        issue.setTaskId(taskId);
        issue.setFilePath(reviewFile.getFilePath());
        issue.setIssueType("AI_REVIEW_FAILED");
        issue.setIssueTypeZh("AI review failed");
        issue.setSeverity("MEDIUM");
        issue.setTitle("AI review failed for this file");
        issue.setDescription("This file could not be reviewed by the AI pipeline: "
                + failureMessage(exception));
        issue.setSuggestion("Retry the review after checking LLM availability, prompt/schema compatibility, and logs.");
        issue.setSource("SYSTEM");
        issue.setCreatedAt(LocalDateTime.now());
        return issue;
    }

    private String failureMessage(Exception exception) {
        Throwable rootCause = rootCause(exception);
        String message = rootCause == null ? null : rootCause.getMessage();
        if (message == null && exception != null) {
            message = exception.getMessage();
        }
        return SensitiveDataSanitizer.redactAndTruncate(message, 500);
    }

    private Throwable rootCause(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record FileReviewOutcome(List<ReviewIssue> issues, Exception failure) {
    }
}
