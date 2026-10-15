package com.codepilot.module.git.client;

import com.codepilot.module.git.config.GithubProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 行内评论的请求体：单行与多行区间（R4）。
 *
 * <p>这是唯一会真的检查"发出去的请求体长什么样"的测试。少了它，
 * `start_line` 被漏发或发错侧都不会有任何测试失败——GitHub 只会返回 422。</p>
 */
class GithubInlineCommentRangeTest {

    private static final String COMMENT_URL = "https://api.github.test/repos/owner/repo/pulls/1/comments";

    private MockRestServiceServer server;

    private GithubClient client;

    @BeforeEach
    void setUp() {
        GithubProperties properties = new GithubProperties();
        properties.setApiBaseUrl("https://api.github.test");
        properties.setToken("token");
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.github.test")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.client = new GithubClient(properties, builder.build(), new ArrayList<Long>()::add);
    }

    @Test
    void shouldSendStartLineAndStartSideForARangedComment() {
        server.expect(requestTo(COMMENT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.line").value(4))
                .andExpect(jsonPath("$.side").value("RIGHT"))
                .andExpect(jsonPath("$.start_line").value(2))
                .andExpect(jsonPath("$.start_side").value("RIGHT"))
                .andRespond(withSuccess());

        client.createPullRequestInlineComment(
                "owner", "repo", 1, "sha", "src/A.java", 4, "RIGHT", 2, "RIGHT", "body");

        server.verify();
    }

    @Test
    void shouldNotSendRangeFieldsForASingleLineComment() {
        server.expect(requestTo(COMMENT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.line").value(4))
                .andExpect(jsonPath("$.start_line").doesNotExist())
                .andExpect(jsonPath("$.start_side").doesNotExist())
                .andRespond(withSuccess());

        client.createPullRequestInlineComment("owner", "repo", 1, "sha", "src/A.java", 4, "RIGHT", "body");

        server.verify();
    }

    /**
     * start_line 必须严格小于 line。相等或倒序时 GitHub 会 422，
     * 因此宁可退化成单行，也不要发出去。
     */
    @Test
    void shouldIgnoreAnInvertedOrDegenerateRange() {
        server.expect(requestTo(COMMENT_URL))
                .andExpect(jsonPath("$.start_line").doesNotExist())
                .andRespond(withSuccess());

        client.createPullRequestInlineComment(
                "owner", "repo", 1, "sha", "src/A.java", 4, "RIGHT", 4, "RIGHT", "body");

        server.verify();
    }

    @Test
    void shouldIgnoreARangeWithoutAStartSide() {
        server.expect(requestTo(COMMENT_URL))
                .andExpect(jsonPath("$.start_line").doesNotExist())
                .andRespond(withSuccess());

        client.createPullRequestInlineComment(
                "owner", "repo", 1, "sha", "src/A.java", 4, "RIGHT", 2, "  ", "body");

        server.verify();
    }

    @Test
    void shouldUseTheSameSideWhenOnlyStartLineIsProvided() {
        server.expect(requestTo(COMMENT_URL))
                .andExpect(jsonPath("$.start_line").value(2))
                .andExpect(jsonPath("$.start_side").value("LEFT"))
                .andExpect(jsonPath("$.side").value("LEFT"))
                .andRespond(withSuccess());

        // 便捷重载：只传 startLine，startSide 默认与 side 相同
        client.createPullRequestInlineComment(
                "owner", "repo", 1, "sha", "src/A.java", 4, "LEFT", 2, "body");

        server.verify();
    }

    @Test
    void shouldSkipPostingWhenTheBodyIsEmpty() {
        // 空正文直接跳过，不应发出任何请求
        client.createPullRequestInlineComment("owner", "repo", 1, "sha", "src/A.java", 4, "RIGHT", "   ");

        server.verify();
    }
}
