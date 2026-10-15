package com.codepilot.module.git.client;

import com.codepilot.module.git.dto.GithubReviewThread;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解析 GitHub GraphQL 的 reviewThreads 响应。
 *
 * <p>这类解析出错的方式很安静——不是报错而是拿到空列表，于是"误报抑制"悄悄失效。
 * 所以这里把结构化的正例与各种畸形输入都覆盖一遍。</p>
 */
class GithubReviewThreadResolverTest {

    private final GithubReviewThreadResolver resolver = new GithubReviewThreadResolver();

    @Test
    void shouldParseThreadsWithResolvedFlagAndFirstCommentAuthor() {
        Map<String, Object> response = response(List.of(
                thread("T1", true, "codepilot-bot[bot]", "<!-- codepilot-finding:0123456789abcdef -->\nbody"),
                thread("T2", false, "codepilot-bot[bot]", "body"),
                thread("T3", true, "human-user", "body")
        ));

        List<GithubReviewThread> threads = resolver.fromGraphqlResponse(response);

        assertThat(threads).hasSize(3);
        assertThat(threads.get(0).threadId()).isEqualTo("T1");
        assertThat(threads.get(0).resolved()).isTrue();
        assertThat(threads.get(0).firstCommentAuthor()).isEqualTo("codepilot-bot[bot]");
        assertThat(threads.get(0).findingFingerprint()).isEqualTo("0123456789abcdef");
        assertThat(threads.get(1).resolved()).isFalse();
        assertThat(threads.get(2).firstCommentAuthor()).isEqualTo("human-user");
    }

    @Test
    void shouldReturnEmptyListForMalformedResponsesInsteadOfThrowing() {
        assertThat(resolver.fromGraphqlResponse(null)).isEmpty();
        assertThat(resolver.fromGraphqlResponse(Map.of())).isEmpty();
        assertThat(resolver.fromGraphqlResponse(Map.of("data", "not-a-map"))).isEmpty();
        assertThat(resolver.fromGraphqlResponse(Map.of("data", Map.of("repository", Map.of())))).isEmpty();
        assertThat(resolver.fromGraphqlResponse(
                Map.of("data", Map.of("repository", Map.of("pullRequest", Map.of("reviewThreads", Map.of()))))))
                .isEmpty();
    }

    @Test
    void shouldSkipNodesThatAreNotMaps() {
        Map<String, Object> response = response(java.util.Arrays.asList(
                thread("T1", true, "bot", "body"), "garbage", null));

        assertThat(resolver.fromGraphqlResponse(response)).hasSize(1);
    }

    @Test
    void shouldIgnoreRepliesAndUseOnlyTheFirstComment() {
        Map<String, Object> comments = Map.of("nodes", List.of(
                Map.of("author", Map.of("login", "codepilot-bot[bot]"), "body", "first"),
                Map.of("author", Map.of("login", "human-user"), "body", "reply")
        ));
        Map<String, Object> response = response(List.of(Map.of(
                "id", "T1", "isResolved", true, "comments", comments)));

        GithubReviewThread thread = resolver.fromGraphqlResponse(response).getFirst();

        assertThat(thread.firstCommentAuthor()).isEqualTo("codepilot-bot[bot]");
        assertThat(thread.firstCommentBody()).isEqualTo("first");
    }

    @Test
    void shouldHandleThreadsWithoutComments() {
        Map<String, Object> response = response(List.of(Map.of(
                "id", "T1", "isResolved", true, "comments", Map.of("nodes", List.of()))));

        GithubReviewThread thread = resolver.fromGraphqlResponse(response).getFirst();

        assertThat(thread.firstCommentAuthor()).isNull();
        assertThat(thread.firstCommentBody()).isEmpty();
        assertThat(thread.findingFingerprint()).isNull();
    }

    @Test
    void shouldReturnNullFingerprintWhenTheMarkerIsAbsentOrMalformed() {
        assertThat(new GithubReviewThread("T", true, "bot", "").findingFingerprint()).isNull();
        assertThat(new GithubReviewThread("T", true, "bot", "no marker here").findingFingerprint()).isNull();
        // 位数不足的指纹必须被拒，否则会拿一个假指纹去撞记忆表
        assertThat(new GithubReviewThread("T", true, "bot", "<!-- codepilot-finding:abc -->").findingFingerprint())
                .isNull();
        // 非十六进制字符同样拒绝
        assertThat(new GithubReviewThread("T", true, "bot", "<!-- codepilot-finding:ZZZZZZZZZZZZZZZZ -->")
                .findingFingerprint()).isNull();
    }

    @Test
    void shouldTolerateWhitespaceInsideTheMarker() {
        GithubReviewThread thread = new GithubReviewThread(
                "T", true, "bot", "<!--   codepilot-finding:0123456789abcdef   -->");

        assertThat(thread.findingFingerprint()).isEqualTo("0123456789abcdef");
    }

    private Map<String, Object> response(List<?> nodes) {
        return Map.of("data", Map.of(
                "repository", Map.of(
                        "pullRequest", Map.of(
                                "reviewThreads", Map.of("nodes", nodes)))));
    }

    private Map<String, Object> thread(String id, boolean resolved, String author, String body) {
        return Map.of(
                "id", id,
                "isResolved", resolved,
                "comments", Map.of("nodes", List.of(
                        Map.of("author", Map.of("login", author), "body", body)))
        );
    }
}
