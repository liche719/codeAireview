package com.codepilot.module.review.report;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommentMarkersTest {

    @Test
    void shouldKeepEveryIdentityMarkerDistinct() {
        // 身份标记全部相同就会重新引入「摘要覆盖 Agent 回复」的缺陷，因此这里做硬约束。
        assertThat(CommentMarkers.appendOnly())
                .doesNotContain(CommentMarkers.REVIEW_SUMMARY)
                .doesNotHaveDuplicates()
                .allSatisfy(marker -> assertThat(marker).isNotBlank());
    }

    @Test
    void shouldMatchMarkerInsideHeaderLines() {
        String body = CommentMarkers.AGENT_REPLY + "\n\n回复正文";

        assertThat(CommentMarkers.matchesHeader(body, CommentMarkers.AGENT_REPLY, 5)).isTrue();
    }

    @Test
    void shouldNotMatchMarkerBeyondHeaderLines() {
        String body = "第一行\n第二行\n第三行\n第四行\n第五行\n第六行\n" + CommentMarkers.AGENT_REPLY;

        assertThat(CommentMarkers.matchesHeader(body, CommentMarkers.AGENT_REPLY, 5)).isFalse();
    }

    @Test
    void shouldTreatEveryKnownMarkerAsBotIdentity() {
        for (String marker : CommentMarkers.appendOnly()) {
            assertThat(CommentMarkers.containsAnyIdentity(marker + "\n\nbody", 5))
                    .as("marker %s should be recognised as bot identity", marker)
                    .isTrue();
        }
        assertThat(CommentMarkers.containsAnyIdentity(
                CommentMarkers.REVIEW_SUMMARY + "\n\nbody", 5)).isTrue();
    }

    @Test
    void shouldRecogniseConfiguredSummaryMarkerPassedAsExtra() {
        String custom = "<!-- my-custom-summary -->";

        assertThat(CommentMarkers.containsAnyIdentity(custom + "\n\nbody", 5, custom)).isTrue();
        // 未传入时不认识——此时依赖 GitHubWebhookService 里的作者过滤兜底
        assertThat(CommentMarkers.containsAnyIdentity(custom + "\n\nbody", 5)).isFalse();
    }

    @Test
    void shouldIgnoreBlankInput() {
        assertThat(CommentMarkers.matchesHeader(null, CommentMarkers.AGENT_REPLY, 5)).isFalse();
        assertThat(CommentMarkers.matchesHeader("body", null, 5)).isFalse();
        assertThat(CommentMarkers.containsAnyIdentity("", 5)).isFalse();
        assertThat(CommentMarkers.containsAnyIdentity(null, 5)).isFalse();
    }

    @Test
    void shouldStripEveryIdentityMarkerFromUntrustedText() {
        String modelOutput = "开头 " + CommentMarkers.REVIEW_SUMMARY
                + " 中间 " + CommentMarkers.AGENT_REPLY
                + " 结尾 " + CommentMarkers.FIX_RESULT;

        String stripped = CommentMarkers.stripIdentities(modelOutput);

        assertThat(stripped)
                .doesNotContain(CommentMarkers.REVIEW_SUMMARY)
                .doesNotContain(CommentMarkers.AGENT_REPLY)
                .doesNotContain(CommentMarkers.FIX_RESULT)
                .contains("开头")
                .contains("结尾");
    }

    @Test
    void shouldStripConfiguredSummaryMarkerWhenProvided() {
        String custom = "<!-- my-custom-summary -->";

        assertThat(CommentMarkers.stripIdentities("a " + custom + " b", custom))
                .isEqualTo("a  b");
    }

    @Test
    void shouldWrapBodyUnderGivenMarker() {
        assertThat(CommentMarkers.wrap(CommentMarkers.AGENT_REPLY, "正文"))
                .isEqualTo(CommentMarkers.AGENT_REPLY + "\n\n正文");
        assertThat(CommentMarkers.wrap(CommentMarkers.AGENT_REPLY, null))
                .isEqualTo(CommentMarkers.AGENT_REPLY + "\n\n");
    }
}
