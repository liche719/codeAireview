package com.codepilot.common.enums;

/**
 * 文件未进入审查的原因分类。
 *
 * <p>{@code review_file.skip_reason} 是人读的自由文本，本枚举是它的机器可读版本——
 * 摘要里的「审查覆盖」区块需要按原因<b>分组</b>展示"哪些文件没看、为什么"，
 * 靠自由文本无法分组统计。</p>
 *
 * <p>两者并存是刻意的：分类用于聚合与告警，reason 用于向用户解释具体原因。</p>
 */
public enum ReviewFileSkipCategory {

    /** patch 为空/null（二进制文件、超大文件，或该文件类型 GitHub 不返回 patch）。 */
    EMPTY_PATCH("空 patch（二进制或超大文件）"),

    /** 文件路径为空，无法定位。 */
    INVALID_PATH("文件路径缺失"),

    /**
     * 构建产物 / 锁文件 / 压缩资源等按路径排除。
     *
     * <p>描述里刻意<b>不再提"生成物"</b>：生成代码已在 R11 拆到 {@link #GENERATED}，
     * 两者处置意图不同（这里只是"不用看"，生成物是"去改生成源"）。
     * 留着旧措辞会让这个分类的描述与它实际覆盖的范围不符。</p>
     */
    PATH_EXCLUDED("构建产物、锁文件或压缩资源路径"),

    /** 第三方 vendor 代码（`vendor/`、`third_party/`、`node_modules/`）——不审，不是我们的代码。 */
    VENDORED("第三方 vendor 代码"),

    /**
     * 生成代码（protobuf / gRPC / 代码生成器产物）。
     *
     * <p>与 {@link #PATH_EXCLUDED} 分开，是因为两者的处置意图不同：
     * 生成物应当改<b>生成源</b>（.proto / 模板），而不是改产物；
     * 看到这个分类就该去上游找问题。</p>
     */
    GENERATED("生成代码（应改生成源）"),

    /** 单文件 patch 超过 max-patch-chars-per-file。 */
    PATCH_TOO_LARGE("单文件 patch 超过上限"),

    /** 任务文件数超过 max-files-per-task。 */
    FILE_COUNT_BUDGET("文件数预算已用尽"),

    /** 任务总 patch 字符数超过 max-total-patch-chars。 */
    TOTAL_PATCH_BUDGET("总 patch 预算已用尽"),

    /** 预算被更高优先级的文件占用（通过优先级排序后未入选）。 */
    BUDGET_PRIORITY("预算分配给了更高优先级的文件"),

    /** 该文件在进入规划前已被标记为跳过。 */
    ALREADY_SKIPPED("进入规划前已标记跳过");

    private final String description;

    ReviewFileSkipCategory(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    /** 是否为「预算类」跳过——这类跳过意味着覆盖度下降，需要在摘要里显著提示。 */
    public boolean isBudgetRelated() {
        return this == FILE_COUNT_BUDGET || this == TOTAL_PATCH_BUDGET || this == BUDGET_PRIORITY;
    }
}
