package com.codepilot.module.github.webhook;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
            // 方法不匹配的处理器在全局 advice 里（控制器内的捕获不到），
            // 所以这里必须把真实 advice 挂上，否则测的是另一套行为。
            .setControllerAdvice(new GlobalExceptionHandler())
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

    /**
     * 浏览器直接打开这个地址（GET）必须回 405，而不是 "HTTP 200 + server error"。
     *
     * <p>这条来自真实反馈：用浏览器访问 webhook 地址看到
     * {@code {"code":500,"message":"server error"}}——状态码、文案、日志三处都不对。</p>
     */
    @Test
    void shouldReturn405ForGetOnWebhookEndpoint() throws Exception {
        mockMvc.perform(get("/api/github/webhook"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405));
    }
}
