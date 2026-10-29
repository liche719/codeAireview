package com.codepilot.module.review.cost;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.module.review.mapper.LlmUsageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * LLM 用量与花费（2026-10-29）。
 *
 * <p><b>为什么单独存一张表</b>：token 原来只在 Micrometer 计数器里（累计值、无维度），
 * 回答不了"最近 7 天花了多少、哪个模型最贵、今天有没有超预算"。企业部署里这是必答题。
 *
 * <p><b>没有单价时 cost 是 null 而不是 0</b>：0 会被读成"没花钱"，null 才是"不知道"。
 * 这个区分写在 {@code estimatedCost} 的注释里，也体现在接口返回上。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmUsageService {

    private final LlmUsageMapper llmUsageMapper;

    /** 记录一次调用；失败只打日志——指标不能拖垮主流程 */
    public void record(String model, Long promptTokens, Long completionTokens, Double estimatedCost) {
        try {
            LlmUsage usage = new LlmUsage();
            usage.setModelName(model);
            usage.setPromptTokens(promptTokens == null ? 0L : promptTokens);
            usage.setCompletionTokens(completionTokens == null ? 0L : completionTokens);
            usage.setEstimatedCost(estimatedCost);
            usage.setCreatedAt(LocalDateTime.now());
            llmUsageMapper.insert(usage);
        } catch (RuntimeException exception) {
            log.warn("Failed to persist llm usage, model={}, message={}", model, exception.getMessage());
        }
    }

    /** 今天已花费（只统计有单价的记录；没有单价的忽略，不按 0 计） */
    public double todayCost() {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        List<LlmUsage> rows = llmUsageMapper.selectList(new LambdaQueryWrapper<LlmUsage>()
                .ge(LlmUsage::getCreatedAt, start));
        return rows.stream()
                .filter(row -> row.getEstimatedCost() != null)
                .mapToDouble(LlmUsage::getEstimatedCost)
                .sum();
    }
}
