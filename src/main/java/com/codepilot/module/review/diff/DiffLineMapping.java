package com.codepilot.module.review.diff;

public record DiffLineMapping(boolean commentable, Integer line, String side) {

    public static DiffLineMapping notCommentable() {
        return new DiffLineMapping(false, null, null);
    }

    public static DiffLineMapping right(Integer line) {
        return new DiffLineMapping(true, line, "RIGHT");
    }
}
