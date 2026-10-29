package com.codepilot.module.review.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败归类（2026-10-29）。
 *
 * <p>归类是为了让人一眼看出"这周失败都是因为什么"。规则刻意保守：认不出就落 `other`，
 * 不要为了好看硬塞进某个已知桶——那样指标会骗人。
 */
class ReviewOpsStatsServiceTest {

    @Test
    @DisplayName("GitHub 鉴权失败要单独成一类")
    void classifiesGithubAuth() {
        assertThat(ReviewOpsStatsService.classifyFailure(
                "failed to get GitHub PR detail: 401 Unauthorized: \"Bad credentials\""))
                .isEqualTo("github-auth");
    }

    @Test
    @DisplayName("限流与超时分开")
    void classifiesRateLimitAndTimeout() {
        assertThat(ReviewOpsStatsService.classifyFailure("403 API rate limit exceeded")).isEqualTo("github-rate-limit");
        assertThat(ReviewOpsStatsService.classifyFailure("Read timed out")).isEqualTo("timeout");
    }

    @Test
    @DisplayName("模型/解析问题归到 llm")
    void classifiesLlmFailures() {
        assertThat(ReviewOpsStatsService.classifyFailure("AI review failed for file X")).isEqualTo("llm");
        assertThat(ReviewOpsStatsService.classifyFailure("Failed to parse AI review result as JSON")).isEqualTo("llm");
    }

    @Test
    @DisplayName("不认识的错误落 other / unknown，而不是猜一个")
    void keepsUnknownHonest() {
        assertThat(ReviewOpsStatsService.classifyFailure("something weird happened")).isEqualTo("other");
        assertThat(ReviewOpsStatsService.classifyFailure("")).isEqualTo("unknown");
        assertThat(ReviewOpsStatsService.classifyFailure(null)).isEqualTo("unknown");
    }
}
