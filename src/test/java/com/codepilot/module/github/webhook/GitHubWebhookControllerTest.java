package com.codepilot.module.github.webhook;

import com.codepilot.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * webhook 的 HTTP 状态码语义。
 *
 * <p>这条测试来自一次真机事故：签名不匹配时接口 body 里写着 {@code code=401}，
 * 但 **HTTP 状态是 200**。对本项目的 JSON 接口来说那是约定，对 GitHub 来说却是
 * "投递成功"——它会因此**永不重投**真正的失败。</p>
 */
class GitHubWebhookControllerTest {

    private final GitHubWebhookService service = mock(GitHubWebhookService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new GitHubWebhookController(service))
            .build();

    @Test
    void shouldReturnReal401WhenSignatureIsInvalid() throws Exception {
        when(service.handle(any(), any(), any(), any()))
                .thenThrow(new BusinessException(401, "invalid GitHub webhook signature"));

        mockMvc.perform(post("/api/github/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "pull_request")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /** 业务异常码不是合法 HTTP 码时不能把它当状态码用，必须退化成 500。 */
    @Test
    void shouldFallBackTo500ForNonHttpBusinessCode() throws Exception {
        when(service.handle(any(), any(), any(), any()))
                .thenThrow(new BusinessException(1001, "internal business rule violated"));

        mockMvc.perform(post("/api/github/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError());
    }

    /** 被忽略的投递仍然是成功（GitHub 不该重投），必须保持 200。 */
    @Test
    void shouldKeep200ForIgnoredDeliveries() throws Exception {
        when(service.handle(any(), any(), any(), any()))
                .thenReturn(GitHubWebhookResponse.ignored("unsupported event"));

        mockMvc.perform(post("/api/github/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("ignored"));
    }

    /** 成功受理也应是 200。 */
    @Test
    void shouldReturn200ForAcceptedDeliveries() throws Exception {
        // 用 any() 而不是 anyString()：没送 X-GitHub-Event 头时 event 是 null，
        // anyString() 不匹配 null，stub 会落空并让 controller 收到 null。
        when(service.handle(any(), any(), any(), any()))
                .thenReturn(GitHubWebhookResponse.processed(1L, "opened"));

        mockMvc.perform(post("/api/github/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "pull_request")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
