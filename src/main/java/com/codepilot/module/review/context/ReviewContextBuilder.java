package com.codepilot.module.review.context;

import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.agent.dto.DismissedFinding;
import com.codepilot.module.review.graph.RepositoryGraphSnapshot;
import com.codepilot.module.review.graph.RepositoryGraphSnapshotBuilder;
import com.codepilot.module.review.memory.ReviewFindingMemoryService;
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

    /**
     * 非空时才读取被 dismiss 的 finding 做 prompt 注入。
     *
     * <p><b>允许为 null 是刻意的</b>：这个类的多数构造重载是给测试用的，
     * 它们关心的是上下文提取逻辑而不是抑制。没有记忆服务 = 没有可注入的 dismissed finding，
     * 这正是"未配置该能力"应有的语义，而不是一个需要到处补默认值的缺陷。</p>
     */
    private final ReviewFindingMemoryService reviewFindingMemoryService;

    ReviewContextBuilder() {
        this(
                new ReviewContextSignalExtractor(),
                new ReviewContextRelationshipExtractor(),
                new ReviewImpactPlanner(),
                new ReviewRelatedPatchExtractor(),
                new RepoSourceExcerptExtractor(),
                new SemanticReviewPlanner(),
                new ReviewLinkedIssueContextProvider(),
                null
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
            ReviewLinkedIssueContextProvider reviewLinkedIssueContextProvider,
            ReviewFindingMemoryService reviewFindingMemoryService
    ) {
        this.reviewContextSignalExtractor = reviewContextSignalExtractor;
        this.reviewContextRelationshipExtractor = reviewContextRelationshipExtractor;
        this.reviewImpactPlanner = reviewImpactPlanner;
        this.reviewRelatedPatchExtractor = reviewRelatedPatchExtractor;
        this.repoSourceExcerptExtractor = repoSourceExcerptExtractor;
        this.semanticReviewPlanner = semanticReviewPlanner;
        this.reviewLinkedIssueContextProvider = reviewLinkedIssueContextProvider;
        this.reviewFindingMemoryService = reviewFindingMemoryService;
    }

    /**
     * 不带记忆服务的七参重载：等价于"没有可注入的 dismissed finding"。
     */
    public ReviewContextBuilder(
            ReviewContextSignalExtractor reviewContextSignalExtractor,
            ReviewContextRelationshipExtractor reviewContextRelationshipExtractor,
            ReviewImpactPlanner reviewImpactPlanner,
            ReviewRelatedPatchExtractor reviewRelatedPatchExtractor,
            RepoSourceExcerptExtractor repoSourceExcerptExtractor,
            SemanticReviewPlanner semanticReviewPlanner,
            ReviewLinkedIssueContextProvider reviewLinkedIssueContextProvider
    ) {
        this(
                reviewContextSignalExtractor,
                reviewContextRelationshipExtractor,
                reviewImpactPlanner,
                reviewRelatedPatchExtractor,
                repoSourceExcerptExtractor,
                semanticReviewPlanner,
                reviewLinkedIssueContextProvider,
                null
        );
    }

    /**
     * 灌入记忆服务的构造重载：供需要验证 prompt 注入的测试使用。
     */
    public ReviewContextBuilder(
            ReviewContextSignalExtractor reviewContextSignalExtractor,
            ReviewContextRelationshipExtractor reviewContextRelationshipExtractor,
            ReviewFindingMemoryService reviewFindingMemoryService
    ) {
        this(
                reviewContextSignalExtractor,
                reviewContextRelationshipExtractor,
                new ReviewImpactPlanner(),
                new ReviewRelatedPatchExtractor(),
                new RepoSourceExcerptExtractor(),
                new SemanticReviewPlanner(),
                new ReviewLinkedIssueContextProvider(),
                reviewFindingMemoryService
        );
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
                new ReviewLinkedIssueContextProvider(),
                null
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
                new ReviewLinkedIssueContextProvider(),
                null
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
                new ReviewLinkedIssueContextProvider(),
                null
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
                new ReviewLinkedIssueContextProvider(),
                null
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
                new ReviewLinkedIssueContextProvider(),
                null
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
        RepositoryGraphSnapshot repositoryGraphSnapshot = RepositoryGraphSnapshotBuilder.buildReviewContextGraph(
                fileSummaries,
                semanticFileContexts,
                repoRelationshipHints,
                null
        );
        List<ReviewContext.LinkedIssueContext> linkedIssueContexts =
                reviewLinkedIssueContextProvider.linkedIssues(task);
        ReviewPlan reviewPlan = semanticReviewPlanner.plan(
                reviewFiles,
                fileSummaries,
                semanticFileContexts,
                repoRelationshipHints,
                reviewImpactPlan,
                repositoryGraphSnapshot,
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
                reviewSignals,
                dismissedFindings(task)
        );
    }

    /**
     * 读取本 PR 上已被维护者 dismiss 的 finding。
     *
     * <p>没有注入记忆服务（多数构造重载的默认情形）时返回空——语义上等于"没有可注入的抑制信息"。
     * 服务本身已经把"功能未开启 / 读取失败"都降级为空列表，这里只是兜住 null 依赖。</p>
     */
    private List<DismissedFinding> dismissedFindings(ReviewTask task) {
        if (reviewFindingMemoryService == null) {
            return List.of();
        }
        return reviewFindingMemoryService.dismissedFindings(task);
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
