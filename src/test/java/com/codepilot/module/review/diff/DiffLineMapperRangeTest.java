package com.codepilot.module.review.diff;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多行区间映射（R4）。
 *
 * <p>GitHub 对 {@code start_line}/{@code line} 有硬约束：同一侧、都在 diff 内、且连续。
 * 这里把每种不满足的情况都固定下来——它们的共同后果是"返回不可评论"，
 * 由调用方<b>降级为单行评论</b>，而不是丢掉这个 finding。</p>
 */
class DiffLineMapperRangeTest {

    private final DiffLineMapper mapper = new DiffLineMapper();

    private static final String PATCH = """
            @@ -1,6 +1,8 @@
             context line one
            -removed line
            +added line one
            +added line two
            +added line three
             context line two
            +added line four
             context line three
            """;

    @Test
    void shouldMapAContiguousRangeOfAddedLines() {
        // 新增侧：added one/two/three 分别落在新行号 2/3/4
        DiffLineMapping mapping = mapper.mapRange("src/A.java", PATCH, 2, 4);

        assertThat(mapping.commentable()).isTrue();
        assertThat(mapping.isRange()).isTrue();
        assertThat(mapping.side()).isEqualTo("RIGHT");
        assertThat(mapping.startLine()).isEqualTo(2);
        assertThat(mapping.line()).isEqualTo(4);
    }

    @Test
    void shouldMapASingleAddedLineAsARangeOfOne() {
        // start == end 不构成区间（GitHub 要求 start_line < line），必须返回不可评论
        DiffLineMapping mapping = mapper.mapRange("src/A.java", PATCH, 3, 3);

        assertThat(mapping.commentable()).isFalse();
    }

    @Test
    void shouldRejectARangeThatSpansContextLines() {
        // 新行号 4 是 added three，5 是 context line two —— 中间隔着上下文行，不连续
        DiffLineMapping mapping = mapper.mapRange("src/A.java", PATCH, 4, 5);

        assertThat(mapping.commentable())
                .as("跨上下文行的区间不是连续区间，必须拒绝")
                .isFalse();
    }

    @Test
    void shouldRejectARangeThatSpansTwoSeparateBlocksOfAddedLines() {
        // 新行号 5 是 context，6 才是 added four —— 中间断了
        DiffLineMapping mapping = mapper.mapRange("src/A.java", PATCH, 2, 6);

        assertThat(mapping.commentable()).isFalse();
    }

    @Test
    void shouldRejectARangeThatCrossesHunks() {
        String twoHunks = """
                @@ -1,3 +1,3 @@
                 context
                +first hunk added
                 context
                @@ -10,3 +10,3 @@
                 context
                +second hunk added
                 context
                """;

        // 2 在第一个 hunk，11 在第二个 hunk
        assertThat(mapper.mapRange("src/A.java", twoHunks, 2, 11).commentable())
                .as("区间不得跨越 hunk 边界")
                .isFalse();
    }

    @Test
    void shouldRejectInvertedOrIncompleteRanges() {
        assertThat(mapper.mapRange("src/A.java", PATCH, 4, 2).commentable()).isFalse();
        assertThat(mapper.mapRange("src/A.java", PATCH, null, 4).commentable()).isFalse();
        assertThat(mapper.mapRange("src/A.java", PATCH, 2, null).commentable()).isFalse();
        assertThat(mapper.mapRange(null, PATCH, 2, 4).commentable()).isFalse();
        assertThat(mapper.mapRange("src/A.java", null, 2, 4).commentable()).isFalse();
        assertThat(mapper.mapRange("src/A.java", "   ", 2, 4).commentable()).isFalse();
    }

    @Test
    void shouldMapADeletedLineRangeToTheLeftSide() {
        String patch = """
                @@ -1,5 +1,3 @@
                 context
                -deleted one
                -deleted two
                 context
                """;

        DiffLineMapping mapping = mapper.mapRange("src/A.java", patch, 2, 3);

        assertThat(mapping.commentable()).isTrue();
        assertThat(mapping.side()).isEqualTo("LEFT");
        assertThat(mapping.startLine()).isEqualTo(2);
        assertThat(mapping.line()).isEqualTo(3);
    }

    @Test
    void shouldNotReturnARangeForSingleLineMappings() {
        // 单行映射的 startLine 必须为 null，否则发布侧会误发 start_line
        DiffLineMapping single = mapper.map("src/A.java", PATCH, 3);

        assertThat(single.commentable()).isTrue();
        assertThat(single.startLine()).isNull();
        assertThat(single.isRange()).isFalse();
    }

    @Test
    void shouldSurviveAMalformedPatchWithoutThrowing() {
        DiffLineMapping mapping = mapper.mapRange("src/A.java", "@@ broken @@\n+line", 1, 2);

        assertThat(mapping.commentable()).isFalse();
    }
}
