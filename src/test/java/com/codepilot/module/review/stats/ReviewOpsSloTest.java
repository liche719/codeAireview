package com.codepilot.module.review.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * SLO 判定（2026-10-29）。
 *
 * <p>三个目标：成功率 ≥95%、p95 ≤60s、积压 ≤300s。这里只测判定逻辑——
 * 它决定"运维看到的红灯是真的还是假的"。**样本不足时不下结论**也是被测的行为。
 */
class ReviewOpsSloTest {

    private final ReviewOpsStatsService service = new ReviewOpsStatsService(mock(JdbcTemplate.class));

    private static Map<String, Object> stats(double successRate, double p90, long samples) {
        Map<String, Object> tasks = new LinkedHashMap<>();
        tasks.put("successRate", successRate);
        Map<String, Object> duration = new LinkedHashMap<>();
        duration.put("p90Seconds", p90);
        duration.put("samples", samples);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("windowDays", 7);
        root.put("tasks", tasks);
        root.put("duration", duration);
        return root;
    }

    private static Map<String, Object> backlog(double oldestWait) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("oldestWaitSeconds", oldestWait);
        return map;
    }

    @SuppressWarnings("unchecked")
    private static boolean ok(Map<String, Object> slo, String name) {
        List<Map<String, Object>> checks = (List<Map<String, Object>>) slo.get("checks");
        return checks.stream()
                .filter(item -> name.equals(item.get("name")))
                .map(item -> (Boolean) item.get("ok"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("全部达标：不报红灯")
    void allGood() {
        Map<String, Object> slo = service.evaluateSlo(stats(0.99, 20, 50), backlog(0));
        assertThat(slo.get("breached")).isEqualTo(false);
        assertThat(ok(slo, "successRate")).isTrue();
        assertThat(ok(slo, "p95Seconds")).isTrue();
        assertThat(ok(slo, "oldestWaitSeconds")).isTrue();
    }

    @Test
    @DisplayName("成功率掉到 83%：必须报红灯（我们自己的事故就被它抓到过）")
    void successRateBreach() {
        Map<String, Object> slo = service.evaluateSlo(stats(0.83, 20, 50), backlog(0));
        assertThat(slo.get("breached")).isEqualTo(true);
        assertThat(ok(slo, "successRate")).isFalse();
    }

    @Test
    @DisplayName("样本不足 5 条时不做耗时判定，避免用 1 个样本下结论")
    void notEnoughSamplesSkipsDurationCheck() {
        Map<String, Object> slo = service.evaluateSlo(stats(1.0, 999, 2), backlog(0));
        assertThat(ok(slo, "p95Seconds")).isTrue();
        assertThat(slo.get("note")).asString().contains("样本少于 5");
    }

    @Test
    @DisplayName("积压超阈值也算违约")
    void backlogBreach() {
        Map<String, Object> slo = service.evaluateSlo(stats(0.99, 20, 50), backlog(900));
        assertThat(slo.get("breached")).isEqualTo(true);
        assertThat(ok(slo, "oldestWaitSeconds")).isFalse();
    }
}
