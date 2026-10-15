package com.codepilot.module.review.memory;

import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubReviewThread;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
 * R7 误报抑制的两条硬约束：
 * <ol>
 *   <li><b>只认"首条评论由本机器人发出"的线程</b>——否则任何人都能开个线程再 resolve 掉，
 *       来关闭我们的 finding。</li>
 *   <li><b>任何失败都降级为"不抑制"</b>——这是旁路能力，GraphQL 权限不足或限流
 *       绝不能让整次审查失败。</li>
 * </ol>
 */
class ReviewDismissalServiceTest {

    private static final String OWNER = "liche719";
    private static final String REPO = "codeAireview";
    private static final int PR = 1;
    private static final String BOT = "codepilot-bot[bot]";
    private static final String FINGERPRINT = "0123456789abcdef";

    private GithubClient githubClient;

    private ReviewFindingMemoryMapper mapper;

    private ReviewProperties properties;

    private ReviewDismissalService service;

    @BeforeEach
    void setUp() {
        githubClient = mock(GithubClient.class);
        mapper = mock(ReviewFindingMemoryMapper.class);
        properties = new ReviewProperties();
        properties.setDismissalSyncEnabled(true);
        service = new ReviewDismissalService(githubClient, mapper, properties);
        when(githubClient.getAuthenticatedUserLogin()).thenReturn(BOT);
        when(mapper.markDismissed(anyString(), anyString(), any(), anyString(), anyString())).thenReturn(1);
    }

    @Test
    void shouldDoNothingWhenDisabled() {
        properties.setDismissalSyncEnabled(false);

        assertThat(service.syncResolvedThreads(task())).isZero();
        verifyNoInteractions(githubClient);
        verifyNoInteractions(mapper);
    }

    @Test
    void shouldMarkResolvedThreadsThatWeOpened() {
        givenThreads(
                thread(true, BOT, FINGERPRINT),
                thread(true, BOT, "fedcba9876543210"));

        assertThat(service.syncResolvedThreads(task())).isEqualTo(2);
        verify(mapper, times(2)).markDismissed(eq(OWNER), eq(REPO), eq(PR), anyString(), anyString());
    }

    /**
     * 关键约束 1：不是我们开的线程，即使被 resolve 也不得抑制。
     */
    @Test
    void shouldIgnoreResolvedThreadsOpenedBySomeoneElse() {
        givenThreads(thread(true, "human-user", FINGERPRINT));

        assertThat(service.syncResolvedThreads(task())).isZero();
        verify(mapper, never()).markDismissed(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void shouldIgnoreThreadsThatAreNotResolvedYet() {
        givenThreads(thread(false, BOT, FINGERPRINT));

        assertThat(service.syncResolvedThreads(task())).isZero();
        verify(mapper, never()).markDismissed(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void shouldIgnoreThreadsWithoutAReadableFindingFingerprint() {
        givenThreads(
                thread(true, BOT, null),
                new GithubReviewThread("T2", true, BOT, "no marker"));

        assertThat(service.syncResolvedThreads(task())).isZero();
    }

    /**
     * 关键约束 2：读线程失败绝不能让审查失败。
     */
    @Test
    void shouldDegradeToNoSuppressionWhenGitHubCannotBeReached() {
        when(githubClient.listReviewThreads(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("graphql permissions missing"));

        assertThatCode(() -> service.syncResolvedThreads(task())).doesNotThrowAnyException();
        assertThat(service.syncResolvedThreads(task())).isZero();
        verify(mapper, never()).markDismissed(anyString(), anyString(), any(), anyString(), anyString());
    }

    /**
     * 拿不到自己的登录名就无法判断线程归属，此时宁可一条都不抑制。
     */
    @Test
    void shouldSuppressNothingWhenTheBotLoginIsUnavailable() {
        when(githubClient.getAuthenticatedUserLogin()).thenReturn(null);
        givenThreads(thread(true, BOT, FINGERPRINT));

        assertThat(service.syncResolvedThreads(task())).isZero();
        verify(mapper, never()).markDismissed(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void shouldSkipWhenTheTaskIsMissingPullRequestIdentity() {
        ReviewTask task = task();
        task.setPrNumber(null);

        assertThat(service.syncResolvedThreads(task)).isZero();
        verifyNoInteractions(githubClient);
    }

    @Test
    void shouldContinueWhenOneUpdateFails() {
        givenThreads(
                thread(true, BOT, FINGERPRINT),
                thread(true, BOT, "fedcba9876543210"));
        when(mapper.markDismissed(eq(OWNER), eq(REPO), eq(PR), eq(FINGERPRINT), anyString()))
                .thenThrow(new IllegalStateException("db down"));

        // 一条失败不应影响另一条，也不应抛出
        assertThat(service.syncResolvedThreads(task())).isEqualTo(1);
    }

    // ---------- helpers ----------

    private void givenThreads(GithubReviewThread... threads) {
        when(githubClient.listReviewThreads(anyString(), anyString(), any()))
                .thenReturn(List.of(threads));
    }

    private GithubReviewThread thread(boolean resolved, String author, String fingerprint) {
        String body = fingerprint == null ? "body" : "<!-- codepilot-finding:" + fingerprint + " -->\nbody";
        return new GithubReviewThread("thread-" + fingerprint + "-" + author, resolved, author, body);
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(7L);
        task.setRepoOwner(OWNER);
        task.setRepoName(REPO);
        task.setPrNumber(PR);
        return task;
    }
}
