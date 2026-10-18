package com.codepilot.common.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.api-auth")
public class ApiAuthProperties {

    private boolean enabled = true;

    private String apiKey = "";

    private String headerName = "X-CodePilot-Api-Key";

    /**
     * 需要鉴权的路径。
     *
     * <p><b>`/actuator/**` 必须在内</b>：`/actuator/metrics` 会列出全部内部指标名与数值
     * （消息积压、HTTP 耗时、JVM 状态），而本服务对公网暴露。
     * 这是真机部署时发现的——不鉴权就能拿到完整指标列表。</p>
     */
    private List<String> protectedPathPatterns = new ArrayList<>(List.of(
            "/api/**",
            "/actuator/**"
    ));

    /**
     * 鉴权排除路径。
     *
     * <ul>
     *   <li>webhook：由 GitHub HMAC 签名校验，不能用 API key（GitHub 不会带我们的头）；</li>
     *   <li>{@code /actuator/health}：探活与监控依赖它，且只返回 {@code {"status":"UP"}}。</li>
     * </ul>
     */
    private List<String> excludePathPatterns = new ArrayList<>(List.of(
            "/api/github/webhook",
            "/api/github/webhook/**",
            "/actuator/health"
    ));
}
