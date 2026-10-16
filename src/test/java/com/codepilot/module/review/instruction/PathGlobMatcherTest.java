package com.codepilot.module.review.instruction;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R12：glob 匹配语义。
 *
 * <p>glob 写错的后果是<b>规则静默地只对一部分文件生效</b>——作者以为覆盖了全仓库，
 * 实际只匹配到根目录。所以这里的重点是边界：{@code **} 能不能匹配 0 层、
 * {@code *} 会不会越过分隔符。</p>
 */
class PathGlobMatcherTest {

    @Test
    void shouldMatchDoubleStarAcrossDirectories() {
        assertThat(PathGlobMatcher.matches("src/main/java/com/example/Demo.java", "src/main/java/**/*.java"))
                .isTrue();
        assertThat(PathGlobMatcher.matches("src/main/java/Demo.java", "src/main/java/**/*.java"))
                .isTrue();
        assertThat(PathGlobMatcher.matches("src/main/java/com/example/Demo.txt", "src/main/java/**/*.java"))
                .isFalse();
    }

    /** {@code **} 必须能匹配"零层目录"，否则 {@code src/x/**}{@code /*.java} 会漏掉直接放在 src/x 下的文件。 */
    @Test
    void shouldLetDoubleStarMatchZeroDirectories() {
        assertThat(PathGlobMatcher.matches("src/Demo.java", "src/**/*.java")).isTrue();
        assertThat(PathGlobMatcher.matches("src/a/b/Demo.java", "src/**/*.java")).isTrue();
    }

    /** 单星不能跨 {@code /}，否则 {@code *.java} 会匹配到子目录里，规则范围被悄悄放大。 */
    @Test
    void shouldNotLetSingleStarCrossDirectoryBoundaries() {
        assertThat(PathGlobMatcher.matches("src/Demo.java", "src/*.java")).isTrue();
        assertThat(PathGlobMatcher.matches("src/nested/Demo.java", "src/*.java")).isFalse();
    }

    /**
     * 不含 {@code /} 的模式按"任意目录下的同名文件"处理。
     *
     * <p>写 {@code *.sql} 的人想要的是"所有 SQL 文件"。
     * 若按字面只匹配根目录，规则会静默失效一大半。</p>
     */
    @Test
    void shouldTreatBarePatternAsAnyDirectory() {
        assertThat(PathGlobMatcher.matches("db/migration/V1__init.sql", "*.sql")).isTrue();
        assertThat(PathGlobMatcher.matches("V1__init.sql", "*.sql")).isTrue();
        assertThat(PathGlobMatcher.matches("src/Demo.java", "*.sql")).isFalse();
    }

    @Test
    void shouldMatchSingleCharacterWildcard() {
        assertThat(PathGlobMatcher.matches("src/a/Demo.java", "src/?/Demo.java")).isTrue();
        assertThat(PathGlobMatcher.matches("src/ab/Demo.java", "src/?/Demo.java")).isFalse();
    }

    /** 路径与模式都归一化后再比较，因此大小写与反斜杠都不影响匹配。 */
    @Test
    void shouldNormalizeBothSides() {
        assertThat(PathGlobMatcher.matches("SRC\\Demo.Java", "src/*.java")).isTrue();
        assertThat(PathGlobMatcher.matches("src/Demo.java", "  SRC/*.JAVA  ")).isTrue();
    }

    /** 正则元字符必须当字面量处理，否则含 {@code .} / {@code +} 的路径会意外变宽。 */
    @Test
    void shouldTreatRegexMetacharactersAsLiterals() {
        assertThat(PathGlobMatcher.matches("src/a+b/Demo.java", "src/a+b/*.java")).isTrue();
        assertThat(PathGlobMatcher.matches("src/aab/Demo.java", "src/a+b/*.java")).isFalse();
        assertThat(PathGlobMatcher.matches("srcXDemo.java", "src.Demo.java")).isFalse();
    }

    @Test
    void shouldNotMatchBlankInputs() {
        assertThat(PathGlobMatcher.matches("src/Demo.java", "  ")).isFalse();
        assertThat(PathGlobMatcher.matches(null, "*.java")).isFalse();
        assertThat(PathGlobMatcher.matches("src/Demo.java", null)).isFalse();
    }
}
