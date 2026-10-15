package com.codepilot.module.review.memory;

import com.codepilot.common.enums.ReviewFileSkipCategory;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.processor.ReviewTaskProcessingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨 headSha 的 finding 记忆与重复抑制。
 *
 * <p>核心问题：{@code synchronize} 会全量重审整个 PR，而新 headSha 又是全新任务，
 * 于是同一个问题会在每次 push 之后被**重复评论**一遍。</p>
 *
 * <h2>安全边界（最重要的一条）</h2>
 * 只有当<b>上一次 head sha 与本次不同</b>、且<b>本次是一次完整审查</b>时，
 * 才允许把"这次没再出现"的 finding 判定为 RESOLVED。
 *
 * <p>如果本次有文件因预算没被审查，某个问题"没再出现"可能只是因为它所在的文件
 * 根本没被看到。把它标成"已修复"是错误结论，比重复评论严重得多。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewFindingMemoryService {

    /** 文件级审查失败时系统生成的 issue 类型；出现它就说明本次审查不完整。 */
    private static final String ISSUE_TYPE_AI_REVIEW_FAILED = "AI_REVIEW_FAILED";

    private static final String REASON_ALREADY_REPORTED = "already reported in a previous review";

    private static final String REASON_ALREADY_REPORTED_SAME_COMMIT = "already reported for this commit";

    private static final String REASON_DISMISSED = "dismissed by a maintainer";

    private final ReviewFindingMemoryMapper reviewFindingMemoryMapper;

    private final ReviewProperties reviewProperties;

    /**
     * 把本次审查的 finding 与历史记忆对账，并把重复项标记为不发布。
     *
     * <p><b>必须在保存 review_issue 之前调用</b>（且与保存同事务），
     * 否则被标记为 SUPPRESS 的状态不会随 issue 一起落库。</p>
     *
     * @return 本次对账的统计，供日志与摘要使用
     */
    public MemoryReconcileResult reconcile(ReviewTask task, ReviewTaskProcessingResult result) {
        if (!reviewProperties.isFindingMemoryEnabled() || task == null || result == null) {
            return MemoryReconcileResult.disabled();
        }
        List<ReviewIssue> issues = result.reviewIssues();
        String repoOwner = task.getRepoOwner();
        String repoName = task.getRepoName();
        Integer prNumber = task.getPrNumber();
        String headSha = task.getHeadSha();
        if (!StringUtils.hasText(repoOwner) || !StringUtils.hasText(repoName) || prNumber == null) {
            return MemoryReconcileResult.disabled();
        }

        List<ReviewFindingMemory> existing = reviewFindingMemoryMapper.findByPullRequest(repoOwner, repoName, prNumber);
        Map<String, ReviewFindingMemory> byFinding = indexBy(existing, ReviewFindingMemory::getFindingFingerprint);
        Map<String, ReviewFindingMemory> byCode = indexBy(existing, ReviewFindingMemory::getCodeFingerprint);

        boolean completeReview = isCompleteReview(result);
        Set<String> matched = new HashSet<>();
        int suppressed = 0;
        int recorded = 0;

        for (ReviewIssue issue : issues) {
            if (issue == null) {
                continue;
            }
            String findingFingerprint = ReviewFindingFingerprint.findingFingerprint(issue);
            String codeFingerprint = ReviewFindingFingerprint.codeFingerprint(issue);

            ReviewFindingMemory known = findingFingerprint == null ? null : byFinding.get(findingFingerprint);
            if (known == null && codeFingerprint != null) {
                known = byCode.get(codeFingerprint);
            }

            if (known != null) {
                matched.add(known.getFindingFingerprint());
                if (suppressRepeated(issue, known, headSha)) {
                    suppressed++;
                }
            }

            if (findingFingerprint != null) {
                upsertActive(task, issue, findingFingerprint, codeFingerprint, known);
                recorded++;
            }
        }

        int resolved = completeReview
                ? resolveMissing(repoOwner, repoName, prNumber, headSha, existing, matched)
                : 0;

        log.info("Review finding memory reconciled, taskId={}, headSha={}, recorded={}, suppressed={}, "
                        + "resolved={}, completeReview={}, memoryEnabled={}",
                task.getId(), headSha, recorded, suppressed, resolved, completeReview, true);

        return new MemoryReconcileResult(true, recorded, suppressed, resolved, completeReview);
    }

    /**
     * @return true 表示这条 finding 已被标记为不发布
     */
    private boolean suppressRepeated(ReviewIssue issue, ReviewFindingMemory known, String headSha) {
        if (!reviewProperties.isSuppressRepeatedFindings()) {
            return false;
        }
        if (ReviewFindingMemory.STATE_DISMISSED.equals(known.getState())) {
            markSuppressed(issue, REASON_DISMISSED);
            return true;
        }
        boolean sameCommit = StringUtils.hasText(headSha) && headSha.equals(known.getLastSeenHeadSha());
        markSuppressed(issue, sameCommit ? REASON_ALREADY_REPORTED_SAME_COMMIT : REASON_ALREADY_REPORTED);
        return true;
    }

    /**
     * 抑制只影响"发不发评论"，finding 本身仍然落库并在摘要里上报条数——
     * 这是刻意的可审计设计，避免出现"静默消失"的问题。
     *
     * <p>已经因其他原因被抑制（例如评分过低）的 issue 保持原有原因，不覆盖。</p>
     */
    private void markSuppressed(ReviewIssue issue, String reason) {
        if ("SUPPRESS".equalsIgnoreCase(issue.getPublishDecision())
                && StringUtils.hasText(issue.getSuppressionReason())) {
            return;
        }
        issue.setPublishDecision("SUPPRESS");
        issue.setSuppressionReason(reason);
    }

    private void upsertActive(
            ReviewTask task,
            ReviewIssue issue,
            String findingFingerprint,
            String codeFingerprint,
            ReviewFindingMemory known
    ) {
        ReviewFindingMemory memory = new ReviewFindingMemory();
        memory.setRepoOwner(task.getRepoOwner());
        memory.setRepoName(task.getRepoName());
        memory.setPrNumber(task.getPrNumber());
        memory.setFindingFingerprint(findingFingerprint);
        memory.setCodeFingerprint(codeFingerprint);
        memory.setIssueType(issue.getIssueType());
        memory.setFilePath(issue.getFilePath());
        memory.setSeverity(issue.getSeverity());
        memory.setTitle(truncate(issue.getTitle(), 255));
        memory.setState(ReviewFindingMemory.STATE_ACTIVE);
        memory.setReopenedCount(known != null && ReviewFindingMemory.STATE_ACTIVE.equals(known.getState()) ? 0 : null);
        memory.setFirstTaskId(task.getId());
        memory.setLastTaskId(task.getId());
        memory.setFirstSeenHeadSha(task.getHeadSha());
        memory.setLastSeenHeadSha(task.getHeadSha());
        // state / reopened_count / first_* 的处理：新记录由 SQL 写默认值，
        // 已存在的记录由 ON CONFLICT 决定是否累加 reopened_count（服务里的 Dismissed 状态在此被重置为 ACTIVE）。
        reviewFindingMemoryMapper.upsertActive(memory);
    }

    /**
     * 把本次没有再现、且历史状态为 ACTIVE 的 finding 标为 RESOLVED。
     *
     * @return 被标记的数量
     */
    private int resolveMissing(
            String repoOwner,
            String repoName,
            Integer prNumber,
            String headSha,
            List<ReviewFindingMemory> existing,
            Set<String> matched
    ) {
        if (!reviewProperties.isAllowResolution()) {
            return 0;
        }
        int resolved = 0;
        for (ReviewFindingMemory memory : existing) {
            if (!ReviewFindingMemory.STATE_ACTIVE.equals(memory.getState())) {
                continue;
            }
            if (matched.contains(memory.getFindingFingerprint())) {
                continue;
            }
            // head sha 没变说明根本没有新提交，"没再现"不能作为已修复的证据。
            if (!StringUtils.hasText(headSha) || headSha.equals(memory.getLastSeenHeadSha())) {
                continue;
            }
            resolved += reviewFindingMemoryMapper.markResolved(
                    repoOwner, repoName, prNumber, memory.getFindingFingerprint(), headSha);
        }
        return resolved;
    }

    /**
     * 本次审查是否"完整"——只有完整审查才敢判定旧问题已修复。
     *
     * <p>两个不完整的信号：有文件因预算被跳过（复用 R2 的 skip_category），
     * 或有文件级 LLM 失败（系统生成了 AI_REVIEW_FAILED）。</p>
     */
    boolean isCompleteReview(ReviewTaskProcessingResult result) {
        List<ReviewFile> files = result.reviewFiles();
        if (files != null) {
            for (ReviewFile file : files) {
                if (file == null || !Boolean.TRUE.equals(file.getSkipped())) {
                    continue;
                }
                if (isBudgetSkip(file.getSkipCategory())) {
                    return false;
                }
            }
        }
        List<ReviewIssue> issues = result.reviewIssues();
        if (issues != null) {
            for (ReviewIssue issue : issues) {
                if (issue != null && ISSUE_TYPE_AI_REVIEW_FAILED.equals(issue.getIssueType())) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isBudgetSkip(String skipCategory) {
        if (!StringUtils.hasText(skipCategory)) {
            return false;
        }
        try {
            return ReviewFileSkipCategory.valueOf(skipCategory).isBudgetRelated();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private Map<String, ReviewFindingMemory> indexBy(
            List<ReviewFindingMemory> memories,
            java.util.function.Function<ReviewFindingMemory, String> keyExtractor
    ) {
        Map<String, ReviewFindingMemory> index = new HashMap<>();
        for (ReviewFindingMemory memory : memories) {
            String key = keyExtractor.apply(memory);
            if (StringUtils.hasText(key)) {
                index.putIfAbsent(key, memory);
            }
        }
        return index;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * 一次对账的结果。
     */
    public record MemoryReconcileResult(
            boolean enabled,
            int recorded,
            int suppressed,
            int resolved,
            boolean completeReview
    ) {
        static MemoryReconcileResult disabled() {
            return new MemoryReconcileResult(false, 0, 0, 0, false);
        }
    }
}
