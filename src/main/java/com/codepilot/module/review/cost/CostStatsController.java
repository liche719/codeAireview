package com.codepilot.module.review.cost;

import com.codepilot.common.response.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 花费统计（2026-10-29）：按天 / 按模型 / 今日预算。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/stats")
public class CostStatsController {

    private final CostStatsService costStatsService;

    @Value("${codepilot.cost.daily-budget-yuan:0}")
    private double dailyBudget;

    @GetMapping("/cost")
    public Result<Map<String, Object>> cost(@RequestParam(defaultValue = "7") int days) {
        return Result.success(costStatsService.snapshot(days, dailyBudget));
    }
}
