package com.codepilot.module.review.memory;

import com.codepilot.common.enums.ReviewFileSkipCategory;
import com.codepilot.module.agent.dto.DismissedFinding;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.processor.ReviewTaskProcessingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R6 的对账逻辑。
 *
 * <p>其中最要紧的是<b>解决条件</b>：只有"本次是完整审查"且"head sha 确实变了"时，
 * 才允许把未再现的 finding 判定为已修复。如果本次有文件因预算没被审查，
 * 某个问题"没再出现"可能只是因为它所在的文件没被看到——把它标成"已修复"是错误结论。</p>
 */
class ReviewFindingMemoryServiceTest {

    private static final String OWNER = "liche719";
    private static final String REPO = "codeAireview";
    private static final int PR = 1;

    private ReviewFindingMemoryMapper mapper;

    private ReviewProperties properties;

    private ReviewFindingMemoryService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ReviewFindingMemoryMapper.class);
        properties = new ReviewProperties();
        properties.setFindingMemoryEnabled(true);
        properties.setSuppressRepeatedFindings(true);
        properties.setAllowResolution(true);
        service = new ReviewFindingMemoryService(mapper, properties);
        when(mapper.findByPullRequest(anyString(), anyString(), any())).thenReturn(List.of());
    }

    @Test
    void shouldDoNothingWhenMemoryIsDisabled() {
        properties.setFindingMemoryEnabled(false);

        ReviewFindingMemoryService.MemoryReconcileResult result =
                service.reconcile(task("sha-1"), result(List.of(issue("SQL_RISK", "字符串拼接 SQL")), List.of()));

        assertThat(result.enabled()).isFalse();
        verifyNoInteractions(mapper);
    }

    @Test
    void shouldPublishEverythingOnTheFirstReview() {
        ReviewIssue issue = issue("SQL_RISK", "字符串拼接 SQL");

        ReviewFindingMemoryService.MemoryReconcileResult result =
                service.reconcile(task("sha-1"), result(List.of(issue), List.of()));

        assertThat(result.suppressed()).isZero();
        assertThat(issue.getPublishDecision()).isEqualTo("PUBLISH");
        verify(mapper, times(1)).upsertActive(any());
    }

    @Test
    void shouldSuppressAFindingAlreadyReportedOnAPreviousCommit() {
        ReviewIssue issue = issue("SQL_RISK", "字符串拼接 SQL");
        givenKnownMemory(issue, ReviewFindingMemory.STATE_ACTIVE, "sha-0");

        ReviewFindingMemoryService.MemoryReconcileResult result =
                service.reconcile(task("sha-1"), result(List.of(issue), List.of()));

        assertThat(result.suppressed()).isEqualTo(1);
        assertThat(issue.getPublishDecision()).isEqualTo("SUPPRESS");
        assertThat(issue.getSuppressionReason()).contains("previous review");
    }

    @Test
    void shouldMarkSuppressedButStillRecordTheFindingSoSuppressionStaysAuditable() {
        ReviewIssue issue = issue("SQL_RISK", "字符串拼接 SQL");
        givenKnownMemory(issue, ReviewFindingMemory.STATE_ACTIVE, "sha-0");

        service.reconcile(task("sha-1"), result(List.of(issue), List.of()));

        // finding 本身仍被写入记忆（并随 review_issue 落库），不会静默消失。
        verify(mapper, times(1)).upsertActive(any());
    }

    @Test
    void shouldNotSuppressWhenSuppressionIsTurnedOff() {
        properties.setSuppressRepeatedFindings(false);
        ReviewIssue issue = issue("SQL_RISK", "字符串拼接 SQL");
        givenKnownMemory(issue, ReviewFindingMemory.STATE_ACTIVE, "sha-0");

        ReviewFindingMemoryService.MemoryReconcileResult result =
                service.reconcile(task("sha-1"), result(List.of(issue), List.of()));

        assertThat(result.suppressed()).isZero();
        assertThat(issue.getPublishDecision()).isEqualTo("PUBLISH");
    }

    @Test
    void shouldSuppressAPreviouslyDismissedFindingEvenOnTheSameCommit() {
        ReviewIssue issue = issue("SQL_RISK", "字符串拼接 SQL");
        givenKnownMemory(issue, ReviewFindingMemory.STATE_DISMISSED, "sha-1");

        service.reconcile(task("sha-1"), result(List.of(issue), List.of()));

        assertThat(issue.getPublishDecision()).isEqualTo("SUPPRESS");
        assertThat(issue.getSuppressionReason()).contains("dismissed");
    }

    /**
     * 安全规则 1：本次审查不完整时，未再现的 finding 绝不能被标为已修复。
     */
    @Test
    void shouldNeverResolveWhenTheReviewWasIncompleteBecauseFilesWereSkipped() {
        ReviewIssue previous = issue("SQL_RISK", "字符串拼接 SQL");
        givenKnownMemory(previous, ReviewFindingMemory.STATE_ACTIVE, "sha-0");

        ReviewFile budgetSkipped = skippedFile("src/Big.java", ReviewFileSkipCategory.FILE_COUNT_BUDGET);
        service.reconcile(task("sha-1"), result(List.of(), List.of(budgetSkipped)));

        verify(mapper, never()).markResolved(anyString(), anyString(), any(), anyString(), anyString());
    }

    /**
     * 安全规则 1b：文件级 LLM 失败同样意味着"没看全"，不得判定已修复。
     */
    @Test
    void shouldNeverResolveWhenTheReviewWasIncompleteBecauseOfAiFailures() {
        ReviewIssue previous = issue("SQL_RISK", "字符串拼接 SQL");
        givenKnownMemory(previous, ReviewFindingMemory.STATE_ACTIVE, "sha-0");

        ReviewIssue aiFailure = issue("AI_REVIEW_FAILED", "This file could not be reviewed");
        service.reconcile(task("sha-1"), result(List.of(aiFailure), List.of()));

        verify(mapper, never()).markResolved(anyString(), anyString(), any(), anyString(), anyString());
    }

    /**
     * 安全规则 2：head sha 没变说明压根没有新提交，"没再现"不能当成已修复的证据。
     */
    @Test
    void shouldNeverResolveWhenTheHeadShaDidNotChange() {
        ReviewIssue previous = issue("SQL_RISK", "字符串拼接 SQL");
        givenKnownMemory(previous, ReviewFindingMemory.STATE_ACTIVE, "sha-1");

        service.reconcile(task("sha-1"), result(List.of(), List.of()));

        verify(mapper, never()).markResolved(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void shouldResolveWhenTheReviewWasCompleteAndTheHeadShaChanged() {
        ReviewIssue previous = issue("SQL_RISK", "字符串拼接 SQL");
        givenKnownMemory(previous, ReviewFindingMemory.STATE_ACTIVE, "sha-0");
        when(mapper.markResolved(eq(OWNER), eq(REPO), eq(PR), anyString(), eq("sha-1"))).thenReturn(1);

        ReviewFindingMemoryService.MemoryReconcileResult result =
                service.reconcile(task("sha-1"), result(List.of(), List.of()));

        assertThat(result.completeReview()).isTrue();
        assertThat(result.resolved()).isEqualTo(1);
        verify(mapper, times(1)).markResolved(eq(OWNER), eq(REPO), eq(PR), anyString(), eq("sha-1"));
    }

    @Test
    void shouldRespectAllowResolutionSwitch() {
        properties.setAllowResolution(false);
        ReviewIssue previous = issue("SQL_RISK", "字符串拼接 SQL");
        givenKnownMemory(previous, ReviewFindingMemory.STATE_ACTIVE, "sha-0");

        service.reconcile(task("sha-1"), result(List.of(), List.of()));

        verify(mapper, never()).markResolved(anyString(), anyString(), any(), anyString(), anyString());
    }

    /**
     * 识别信息不足的 finding 永不抑制——拿粗糙指纹去撞会导致漏报。
     */
    @Test
    void shouldNeverSuppressAFindingWithoutEnoughIdentityInformation() {
        ReviewIssue anonymous = new ReviewIssue();
        anonymous.setIssueType("UNKNOWN");
        anonymous.setFilePath("src/A.java");
        anonymous.setPublishDecision("PUBLISH");

        ReviewFindingMemoryService.MemoryReconcileResult result =
                service.reconcile(task("sha-1"), result(List.of(anonymous), List.of()));

        assertThat(result.suppressed()).isZero();
        assertThat(result.recorded()).isZero();
        verify(mapper, never()).upsertActive(any());
    }

    @Test
    void shouldReportIncompleteReviewButStillRecordFindings() {
        ReviewIssue issue = issue("SQL_RISK", "字符串拼接 SQL");
        ReviewFile budgetSkipped = skippedFile("src/Big.java", ReviewFileSkipCategory.TOTAL_PATCH_BUDGET);

        ReviewFindingMemoryService.MemoryReconcileResult result =
                service.reconcile(task("sha-1"), result(List.of(issue), List.of(budgetSkipped)));

        assertThat(result.completeReview()).isFalse();
        assertThat(result.recorded()).isEqualTo(1);
    }

    // ---------- helpers ----------

    // ------------------------------------------------------------------
    // R7 的 prompt 注入读取路径
    // ------------------------------------------------------------------

    private static ReviewFindingMemory memory(String state, String filePath, String issueType, String title) {
        ReviewFindingMemory memory = new ReviewFindingMemory();
        memory.setState(state);
        memory.setFilePath(filePath);
        memory.setIssueType(issueType);
        memory.setTitle(title);
        return memory;
    }

    /**
     * 注入必须挂在 {@code finding-memory-enabled} 上。
     *
     * <p>本项目的硬约定是"抑制必须可审计"。prompt 注入让模型根本不生成这条 finding，
     * 于是 {@code review_issue} 里不会有任何记录——它只能是 R6 可审计抑制之上的省 token 优化。
     * 绑在同一个开关上就保证了：注入生效时落库路径也一定生效。</p>
     */
    @Test
    void shouldNotReadDismissedFindingsWhenMemoryIsDisabled() {
        properties.setFindingMemoryEnabled(false);

        assertThat(service.dismissedFindings(task("sha-1"))).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void shouldReturnOnlyDismissedFindings() {
        when(mapper.findByPullRequest(anyString(), anyString(), any())).thenReturn(List.of(
                memory(ReviewFindingMemory.STATE_DISMISSED, "src/A.java", "SQL_RISK", "keep me out"),
                memory(ReviewFindingMemory.STATE_ACTIVE, "src/B.java", "BUG_RISK", "still open"),
                memory(ReviewFindingMemory.STATE_RESOLVED, "src/C.java", "STYLE", "already fixed")
        ));

        List<DismissedFinding> dismissed = service.dismissedFindings(task("sha-1"));

        assertThat(dismissed).hasSize(1);
        assertThat(dismissed.get(0).filePath()).isEqualTo("src/A.java");
        assertThat(dismissed.get(0).title()).isEqualTo("keep me out");
    }

    /** 旁路优化：读不到记忆就当没有可注入的内容，绝不让审查失败。 */
    @Test
    void shouldDegradeToEmptyWhenMemoryCannotBeRead() {
        when(mapper.findByPullRequest(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("db is down"));

        assertThat(service.dismissedFindings(task("sha-1"))).isEmpty();
    }

    @Test
    void shouldCapDismissedFindingsToKeepThePromptBounded() {
        List<ReviewFindingMemory> memories = new ArrayList<>();
        for (int index = 0; index < 80; index++) {
            memories.add(memory(ReviewFindingMemory.STATE_DISMISSED,
                    "src/F" + index + ".java", "BUG_RISK", "issue " + index));
        }
        when(mapper.findByPullRequest(anyString(), anyString(), any())).thenReturn(memories);

        assertThat(service.dismissedFindings(task("sha-1"))).hasSize(50);
    }

    @Test
    void shouldNotReadDismissedFindingsWithoutPullRequestIdentity() {
        ReviewTask withoutPr = task("sha-1");
        withoutPr.setPrNumber(null);

        assertThat(service.dismissedFindings(withoutPr)).isEmpty();
        assertThat(service.dismissedFindings(null)).isEmpty();
        verifyNoInteractions(mapper);
    }

    private void givenKnownMemory(ReviewIssue issue, String state, String lastSeenHeadSha) {
        ReviewFindingMemory memory = new ReviewFindingMemory();
        memory.setRepoOwner(OWNER);
        memory.setRepoName(REPO);
        memory.setPrNumber(PR);
        memory.setFindingFingerprint(ReviewFindingFingerprint.findingFingerprint(issue));
        memory.setCodeFingerprint(ReviewFindingFingerprint.codeFingerprint(issue));
        memory.setState(state);
        memory.setLastSeenHeadSha(lastSeenHeadSha);
        when(mapper.findByPullRequest(anyString(), anyString(), any())).thenReturn(List.of(memory));
    }

    private ReviewTask task(String headSha) {
        ReviewTask task = new ReviewTask();
        task.setId(7L);
        task.setRepoOwner(OWNER);
        task.setRepoName(REPO);
        task.setPrNumber(PR);
        task.setHeadSha(headSha);
        return task;
    }

    private ReviewTaskProcessingResult result(List<ReviewIssue> issues, List<ReviewFile> files) {
        return new ReviewTaskProcessingResult(files.size(), issues.size(), "HIGH", files, issues);
    }

    private ReviewIssue issue(String issueType, String title) {
        ReviewIssue issue = new ReviewIssue();
        issue.setIssueType(issueType);
        issue.setFilePath("src/main/java/com/example/UserService.java");
        issue.setLineNumber(42);
        issue.setTitle(title);
        issue.setDescription(title);
        issue.setSuggestion("use parameter binding");
        issue.setSeverity("HIGH");
        issue.setPublishDecision("PUBLISH");
        return issue;
    }

    private ReviewFile skippedFile(String path, ReviewFileSkipCategory category) {
        ReviewFile file = new ReviewFile();
        file.setFilePath(path);
        file.setSkipped(true);
        file.setSkipCategory(category.name());
        return file;
    }
}
