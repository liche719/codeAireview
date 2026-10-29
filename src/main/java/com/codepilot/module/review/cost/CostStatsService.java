package com.codepilot.module.review.cost;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 花费统计（2026-10-29）。
 *
 * <p>回答三个问题：这段时间花了多少、钱花在哪个模型上、今天的预算还剩多少。
 *
 * <p><b>明确的能力边界</b>：目前统计到"按天 / 按模型"。
 * **按仓库还做不到**——LLM 客户端的调用点拿不到 task/repo 上下文（
 * `LangChain4jReviewLlmClient.recordUsage` 只有 model 与 token）。
 * 要按仓库统计，需要把 taskId 一路透传到 LLM 客户端，属于独立一轮的改动。
 * 这里如实写在接口返回里，而不是编一个"按仓库"的假维度。
 */
@Service
@RequiredArgsConstructor
public class CostStatsService {

    private final JdbcTemplate jdbcTemplate;

    private final LlmUsageService llmUsageService;

    public Map<String, Object> snapshot(int days, double dailyBudget) {
        int window = Math.max(1, Math.min(90, days));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowDays", window);
        result.put("byDay", query(
                "SELECT to_char(created_at, 'YYYY-MM-DD') AS bucket, "
                        + "COUNT(*) AS calls, SUM(prompt_tokens) AS prompt, SUM(completion_tokens) AS completion, "
                        + "SUM(COALESCE(estimated_cost, 0)) AS cost "
                        + "FROM llm_usage WHERE created_at >= now() - (? || ' days')::interval "
                        + "GROUP BY 1 ORDER BY 1 DESC", String.valueOf(window)));
        result.put("byModel", query(
                "SELECT COALESCE(model_name, 'unknown') AS bucket, COUNT(*) AS calls, "
                        + "SUM(prompt_tokens) AS prompt, SUM(completion_tokens) AS completion, "
                        + "SUM(COALESCE(estimated_cost, 0)) AS cost "
                        + "FROM llm_usage WHERE created_at >= now() - (? || ' days')::interval "
                        + "GROUP BY 1 ORDER BY cost DESC", String.valueOf(window)));
        double today = llmUsageService.todayCost();
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("dailyBudget", dailyBudget);
        budget.put("todayCost", Math.round(today * 10000) / 10000.0);
        budget.put("exceeded", dailyBudget > 0 && today >= dailyBudget);
        result.put("budget", budget);
        result.put("note", "按仓库统计暂未支持：LLM 客户端拿不到 task 上下文");
        return result;
    }

    private List<Map<String, Object>> query(String sql, String days) {
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bucket", rs.getString("bucket"));
            row.put("calls", rs.getLong("calls"));
            row.put("promptTokens", rs.getLong("prompt"));
            row.put("completionTokens", rs.getLong("completion"));
            row.put("cost", Math.round(rs.getDouble("cost") * 10000) / 10000.0);
            rows.add(row);
        }, days);
        return rows;
    }
}
