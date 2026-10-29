package com.codepilot.module.review.controller;

import com.codepilot.common.response.Result;
import com.codepilot.module.review.stats.ReviewOpsStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 审查运营指标（2026-10-29）。
 *
 * <p>补的是 `/actuator/metrics` 给不了的那部分：**时间窗口内的**成功率、耗时分位、失败类型分布、
 * 发布成功率与积压。计数器只有累计值，问"最近 7 天怎么样"要自己隔时间取差值——这个接口一次答完。
 *
 * <p>放在 `/api/**` 下，因此受项目统一的 API Key 过滤器保护（`CODEPILOT_API_AUTH_*`）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/stats")
public class ReviewOpsStatsController {

    private final ReviewOpsStatsService statsService;

    @GetMapping("/review")
    public Result<Map<String, Object>> review(@RequestParam(defaultValue = "7") int days) {
        return Result.success(statsService.snapshot(days));
    }
}
