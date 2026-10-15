package com.codepilot.module.review.diff;

/**
 * 一个 finding 到 diff 行的映射结果。
 *
 * @param commentable 是否可评论
 * @param line        锚定行（区间模式下是<b>结束行</b>）
 * @param side        {@code RIGHT}（新增侧）或 {@code LEFT}（删除侧）
 * @param startLine   区间模式下是<b>起始行</b>；单行模式为 {@code null}
 */
public record DiffLineMapping(boolean commentable, Integer line, String side, Integer startLine) {

    public static DiffLineMapping notCommentable() {
        return new DiffLineMapping(false, null, null, null);
    }

    public static DiffLineMapping right(Integer line) {
        return new DiffLineMapping(true, line, "RIGHT", null);
    }

    public static DiffLineMapping left(Integer line) {
        return new DiffLineMapping(true, line, "LEFT", null);
    }

    /**
     * 多行区间。GitHub 要求 {@code start_line} 与 {@code line} 在同一侧、且都在 diff 内。
     */
    public static DiffLineMapping range(String side, Integer startLine, Integer endLine) {
        return new DiffLineMapping(true, endLine, side, startLine);
    }

    public boolean isRange() {
        return commentable && startLine != null && line != null && startLine < line;
    }
}
