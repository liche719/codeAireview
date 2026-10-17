package com.codepilot.common.enums;

import java.util.Locale;

/**
 * 评论发布通道。
 *
 * <h2>为什么需要 {@link #BOTH}</h2>
 * 原先只有"只发摘要"和"只发行内"两种，而 webhook 的分支逻辑是
 * <b>PR opened → 只发行内</b>、<b>其余（含 synchronize）→ 只发摘要</b>。
 * 结果是：
 * <ul>
 *   <li>PR 刚打开时没有摘要——而摘要承载"本次只审了 N/M 个文件，不代表 PR 没有问题"
 *       这条覆盖度声明（R2）；</li>
 *   <li>之后的每次推送只有摘要，<b>行内评论再也不会出现</b>——恰恰是复审最需要它的场景。</li>
 * </ul>
 *
 * <p>这是真机验证时发现的：沙箱 PR 上只有 1 条摘要，8 条可发布的 finding
 * 一条都没落到行内。业界工具（reviewdog / PR-Agent / CodeRabbit）都是两者同时给：
 * 摘要给总览与覆盖度，行内给定位。</p>
 */
public enum ReviewCommentMode {

    SUMMARY_ONLY,
    INLINE_ONLY,
    BOTH;

    public static ReviewCommentMode fromValue(String value) {
        if (value == null || value.isBlank()) {
            return SUMMARY_ONLY;
        }
        try {
            return ReviewCommentMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return SUMMARY_ONLY;
        }
    }

    public boolean isInlineOnly() {
        return this == INLINE_ONLY;
    }

    /** 是否要发行内评论（INLINE_ONLY 与 BOTH）。 */
    public boolean includesInline() {
        return this == INLINE_ONLY || this == BOTH;
    }

    /** 是否要发摘要评论（SUMMARY_ONLY 与 BOTH）。 */
    public boolean includesSummary() {
        return this == SUMMARY_ONLY || this == BOTH;
    }
}
