package com.codepilot.module.review.instruction;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析仓库内的路径级规则文件（R12）。
 *
 * <h2>文件格式</h2>
 * Markdown，二级标题即 glob，标题下的正文即该路径的审查要求：
 *
 * <pre>
 * ## src/main/java/**&#47;*.java
 * 所有 public 方法必须有 javadoc。
 *
 * ## **&#47;*.sql
 * 迁移必须可回滚，且不得锁表超过一秒。
 * </pre>
 *
 * <p>选择"标题即 glob"而不是 YAML：这份文件会随 PR 一起被 review，
 * Markdown 让"规则本身"也能被人一眼读懂并讨论。首个二级标题之前的内容视为前言，忽略。</p>
 *
 * <h2>为什么要设上限</h2>
 * 这份内容会被拼进<b>每个匹配文件</b>的 prompt。不加限制的话，
 * 一个被提交进来的巨大规则文件会成倍放大 token 消耗并挤掉真正的代码上下文。
 * 因此单条指令、总长度、条目数都有上限，超出即截断/丢弃——<b>降级而不是失败</b>。
 */
public final class PathInstructionParser {

    /** 单条指令的最大字符数；超出截断并加标记。 */
    static final int MAX_INSTRUCTION_CHARS = 2000;

    /** 最多解析多少条规则；超出忽略其余。 */
    static final int MAX_SECTIONS = 50;

    private static final String SECTION_PREFIX = "##";

    private static final String TRUNCATION_MARKER = "…[truncated]";

    private PathInstructionParser() {
    }

    /**
     * @param content 规则文件全文；null/空白返回空列表
     * @param maxTotalChars 全部指令合计的字符上限（含 glob），超出后忽略后续条目
     */
    public static List<PathInstruction> parse(String content, int maxTotalChars) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        int budget = Math.max(0, maxTotalChars);
        List<PathInstruction> instructions = new ArrayList<>();
        String glob = null;
        StringBuilder body = new StringBuilder();

        for (String line : content.split("\\R", -1)) {
            if (isSectionHeading(line)) {
                appendIfUsable(instructions, glob, body, budget);
                glob = line.substring(SECTION_PREFIX.length()).trim();
                body.setLength(0);
                if (instructions.size() >= MAX_SECTIONS) {
                    break;
                }
                continue;
            }
            if (glob != null) {
                body.append(line).append('\n');
            }
        }
        appendIfUsable(instructions, glob, body, budget);
        return List.copyOf(instructions);
    }

    /**
     * 只认正好以 {@code ##} 开头的行，且后面必须有内容。
     *
     * <p>刻意不认 {@code #}（一级标题常被用作文件标题）与 {@code ###} 及更深
     * （三级标题常被用作说明子项）——把说明性标题误当成 glob 会生成一条
     * <b>永远匹配不到任何文件</b>的规则，而作者会以为它生效了。</p>
     *
     * <p>判定是"第三个字符不是 {@code #}"。只判断"是否以四个 {@code #} 开头"是不够的，
     * 那样 {@code ### 例外} 会被当成 {@code # 例外} 这个 glob 收下——这正是最初写错的地方，
     * 由 {@code PathInstructionParserTest.shouldNotTreatLowerLevelHeadingsAsGlobs} 抓出来。</p>
     */
    private static boolean isSectionHeading(String line) {
        if (line == null || !line.startsWith(SECTION_PREFIX)) {
            return false;
        }
        if (line.length() <= SECTION_PREFIX.length() || line.charAt(SECTION_PREFIX.length()) == '#') {
            return false;
        }
        return StringUtils.hasText(line.substring(SECTION_PREFIX.length()).trim());
    }

    private static void appendIfUsable(List<PathInstruction> instructions, String glob, StringBuilder body, int budget) {
        if (!StringUtils.hasText(glob) || instructions.size() >= MAX_SECTIONS) {
            return;
        }
        String instruction = body.toString().trim();
        if (!StringUtils.hasText(instruction)) {
            return;
        }
        if (instruction.length() > MAX_INSTRUCTION_CHARS) {
            instruction = instruction.substring(0, MAX_INSTRUCTION_CHARS) + TRUNCATION_MARKER;
        }
        int used = instructions.stream()
                .mapToInt(existing -> existing.glob().length() + existing.instruction().length())
                .sum();
        if (used + glob.length() + instruction.length() > budget) {
            return;
        }
        instructions.add(new PathInstruction(glob.trim(), instruction));
    }
}
