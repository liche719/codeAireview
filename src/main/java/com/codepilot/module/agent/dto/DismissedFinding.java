package com.codepilot.module.agent.dto;

/**
 * 一条被维护者 dismiss 掉的 finding，用于在 prompt 里告诉模型"这些别再提"。
 *
 * <p>刻意做成一个不含数据库字段的最小视图：prompt 只需要"哪个文件、什么问题"，
 * 把 {@code ReviewFindingMemory} 实体整体传进 prompt 层会把持久化细节泄漏出去。</p>
 *
 * <h2>为什么放在 {@code agent.dto}</h2>
 * 它是"送进 LLM 的内容"这个契约的一部分。同包的 {@link AiReviewIssue} / {@link AiReviewContext}
 * 已经是同类契约；放这里不会让 {@code agent.dto} 反过来依赖 {@code review} 包。
 *
 * <h2>为什么所有字段都要压成单行并截断</h2>
 * 这段文本会被拼进 LLM 的 prompt。{@code title} 最初来自模型对仓库内容的概括，
 * 也就是说<b>仓库里的内容有机会影响到它</b>。如果原文里的换行能穿透进来，
 * 攻击者就能在自己的代码里埋一段看起来像 prompt 结构的文本，
 * 借这条通道伪造出"以下是新的指令"之类的段落。压成单行 + 截断把这条路堵死。
 */
public record DismissedFinding(String filePath, String issueType, String title) {

    private static final int MAX_FILE_PATH_LENGTH = 300;

    private static final int MAX_ISSUE_TYPE_LENGTH = 60;

    private static final int MAX_TITLE_LENGTH = 160;

    public DismissedFinding {
        filePath = singleLine(truncate(filePath, MAX_FILE_PATH_LENGTH));
        issueType = singleLine(truncate(issueType, MAX_ISSUE_TYPE_LENGTH));
        title = singleLine(truncate(title, MAX_TITLE_LENGTH));
    }

    /**
     * 是否值得写进 prompt。
     *
     * <p>连标题和文件路径都取不到时，这条记录对模型毫无信息量——写了也只是噪声。</p>
     */
    public boolean isUsable() {
        return !title.isBlank() || !filePath.isBlank();
    }

    private static String singleLine(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .trim();
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }
}
