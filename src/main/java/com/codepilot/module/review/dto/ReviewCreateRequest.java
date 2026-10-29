package com.codepilot.module.review.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ReviewCreateRequest {

    @NotBlank
    private String prUrl;

    /**
     * 评论形态（2026-10-29 新增）：{@code BOTH} / {@code SUMMARY_ONLY} / {@code INLINE_ONLY}。
     *
     * <p>为什么加：原来只有 Webhook 能决定评论形态（`pull_request` 事件用 BOTH），
     * **通过 API 创建的任务一律 SUMMARY_ONLY**——于是"手动触发一次审查"永远拿不到行内评论。
     * 这条是端到端验证时发现的：任务明明 7 个文件、20 条问题，PR 上却只有一段摘要。
     *
     * <p>不填时保持原行为（SUMMARY_ONLY），不破坏现有调用方。
     */
    private String commentMode;
}
