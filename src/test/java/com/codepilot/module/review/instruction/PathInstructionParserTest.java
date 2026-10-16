package com.codepilot.module.review.instruction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R12：规则文件解析。
 *
 * <p>关键在于"什么不该被当成 glob"。把说明性标题误认成 glob，会生成一条
 * <b>永远匹配不到任何文件</b>的规则——作者以为它生效了，实际上什么都没有发生。</p>
 */
class PathInstructionParserTest {

    @Test
    void shouldParseHeadingAsGlobAndBodyAsInstruction() {
        List<PathInstruction> instructions = PathInstructionParser.parse("""
                # 仓库审查规则

                ## src/main/java/**/*.java
                所有 public 方法必须有 javadoc。

                ## **/*.sql
                迁移必须可回滚。
                """, 8000);

        assertThat(instructions).hasSize(2);
        assertThat(instructions.get(0).glob()).isEqualTo("src/main/java/**/*.java");
        assertThat(instructions.get(0).instruction()).isEqualTo("所有 public 方法必须有 javadoc。");
        assertThat(instructions.get(1).glob()).isEqualTo("**/*.sql");
        assertThat(instructions.get(1).instruction()).isEqualTo("迁移必须可回滚。");
    }

    @Test
    void shouldKeepMultiLineInstructions() {
        List<PathInstruction> instructions = PathInstructionParser.parse("""
                ## **/*.sql
                迁移必须可回滚。
                不得锁表超过一秒。
                """, 8000);

        assertThat(instructions).hasSize(1);
        assertThat(instructions.getFirst().instruction())
                .contains("迁移必须可回滚。")
                .contains("不得锁表超过一秒。");
    }

    /** 一级标题是文件标题，不是 glob。 */
    @Test
    void shouldIgnoreTopLevelHeadingAsPreamble() {
        List<PathInstruction> instructions = PathInstructionParser.parse("""
                # 这里写文件说明
                这段是前言，不是规则。

                ## *.sql
                必须可回滚。
                """, 8000);

        assertThat(instructions).hasSize(1);
        assertThat(instructions.getFirst().glob()).isEqualTo("*.sql");
    }

    /** 三级标题是说明子项，不是 glob——误认会让规则静默失效。 */
    @Test
    void shouldNotTreatLowerLevelHeadingsAsGlobs() {
        List<PathInstruction> instructions = PathInstructionParser.parse("""
                ## **/*.java
                ### 例外
                生成的代码不受此限。
                """, 8000);

        assertThat(instructions).hasSize(1);
        assertThat(instructions.getFirst().glob()).isEqualTo("**/*.java");
        assertThat(instructions.getFirst().instruction()).contains("### 例外").contains("生成的代码不受此限。");
    }

    @Test
    void shouldSkipSectionsWithoutBody() {
        List<PathInstruction> instructions = PathInstructionParser.parse("""
                ## src/A.java
                ## src/B.java
                有内容。
                """, 8000);

        assertThat(instructions).hasSize(1);
        assertThat(instructions.getFirst().glob()).isEqualTo("src/B.java");
    }

    @Test
    void shouldReturnEmptyForBlankContent() {
        assertThat(PathInstructionParser.parse(null, 8000)).isEmpty();
        assertThat(PathInstructionParser.parse("   \n  ", 8000)).isEmpty();
        assertThat(PathInstructionParser.parse("# 只有标题", 8000)).isEmpty();
    }

    /** 单条指令超长要截断并标记，不能整段吃掉 prompt 预算。 */
    @Test
    void shouldTruncateOverlongInstruction() {
        String longBody = "x".repeat(PathInstructionParser.MAX_INSTRUCTION_CHARS + 500);

        List<PathInstruction> instructions = PathInstructionParser.parse(
                "## *.java\n" + longBody, 100_000);

        assertThat(instructions).hasSize(1);
        assertThat(instructions.getFirst().instruction())
                .hasSize(PathInstructionParser.MAX_INSTRUCTION_CHARS + "…[truncated]".length())
                .endsWith("…[truncated]");
    }

    /**
     * 总预算超限时<b>丢弃后续条目而不是截断已有条目</b>。
     *
     * <p>截断一条规则会改变它的意思（"必须可回滚，且…"被切一半仍像完整规则），
     * 而少几条规则只是覆盖变窄——后者更安全。</p>
     */
    @Test
    void shouldStopWhenTotalBudgetIsExhausted() {
        String content = """
                ## a.java
                第一条规则内容。
                ## b.java
                第二条规则内容。
                ## c.java
                第三条规则内容。
                """;

        List<PathInstruction> instructions = PathInstructionParser.parse(content, 40);

        assertThat(instructions).hasSizeLessThan(3);
        assertThat(instructions).allSatisfy(instruction ->
                assertThat(instruction.instruction()).doesNotContain("truncated"));
    }

    @Test
    void shouldCapTheNumberOfSections() {
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < PathInstructionParser.MAX_SECTIONS + 20; index++) {
            content.append("## file").append(index).append(".java\n规则 ").append(index).append('\n');
        }

        assertThat(PathInstructionParser.parse(content.toString(), 1_000_000))
                .hasSize(PathInstructionParser.MAX_SECTIONS);
    }
}
