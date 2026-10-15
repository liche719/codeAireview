package com.codepilot.module.review.report;

import org.springframework.util.StringUtils;

import java.util.List;

/**
 * GitHub 评论身份标记。
 *
 * <p>本项目会在同一个 PR 上发布多种评论，每种用途必须使用<b>互不相同</b>的身份标记：
 * 审查摘要属于「可更新」——它靠标记找回自己上一次发的评论并覆盖，避免刷屏；
 * 而 Agent 回复、命令提示、修复结果都属于「只追加」，永远不会被覆盖。</p>
 *
 * <p>如果这几类共用同一个标记，摘要更新就会命中并覆盖掉其他评论，静默丢失用户可见内容。
 * 因此这里是唯一的标记来源，任何新增的评论发布路径都必须从这里取标记。</p>
 *
 * <p>标记刻意<b>不做成配置项</b>：身份一致性是这个机制的全部意义，允许配置只会引入
 * "两个标记被配成同一个值" 的风险。唯一例外是审查摘要标记——它历史上就可通过
 * {@code codepilot.github.comment-marker} 覆盖，为兼容既有部署而保留该配置。</p>
 */
public final class CommentMarkers {

    /** 审查摘要（可更新）。这是默认值，可被 {@code codepilot.github.comment-marker} 覆盖。 */
    public static final String REVIEW_SUMMARY = "<!-- codepilot-ai-review:liche719/codeAireview -->";

    /** Agent 对话回复（只追加）。 */
    public static final String AGENT_REPLY = "<!-- codepilot-agent-reply -->";

    /** 命令不可用或不被支持时的提示（只追加）。 */
    public static final String COMMAND_NOTICE = "<!-- codepilot-command-notice -->";

    /** 修复流程结果通知，例如预演完成、已推送、已创建修复 PR（只追加）。 */
    public static final String FIX_RESULT = "<!-- codepilot-fix-result -->";

    /**
     * 身份标记只在评论正文的前 N 行内匹配。
     *
     * <p>避免正文里**引用**到某个标记（例如用户把标记贴进评论、或我们的摘要里提到它）时被误判
     * 成身份标记。PR-Agent 同样采用"只在正文头部若干行内精确匹配"的做法。</p>
     */
    public static final int DEFAULT_HEADER_LINES = 5;

    private static final List<String> APPEND_ONLY = List.of(AGENT_REPLY, COMMAND_NOTICE, FIX_RESULT);

    private CommentMarkers() {
    }

    /** 所有「只追加」标记；审查摘要标记需另行传入，因为它是可配置的。 */
    public static List<String> appendOnly() {
        return APPEND_ONLY;
    }

    /**
     * 判断指定标记是否出现在正文前 {@code headerLines} 行内。
     */
    public static boolean matchesHeader(String body, String marker, int headerLines) {
        if (!StringUtils.hasText(body) || !StringUtils.hasText(marker)) {
            return false;
        }
        int limit = Math.max(1, headerLines);
        return body.lines()
                .limit(limit)
                .anyMatch(line -> line.contains(marker));
    }

    /**
     * 正文前 {@code headerLines} 行内是否含<b>任意</b>已知身份标记。
     *
     * <p>用于 webhook 入口的"这是不是机器人自己发的评论"判定，避免机器人回复触发自己。</p>
     *
     * @param extraMarkers 额外的标记，用于传入可配置的审查摘要标记
     */
    public static boolean containsAnyIdentity(String body, int headerLines, String... extraMarkers) {
        if (!StringUtils.hasText(body)) {
            return false;
        }
        if (matchesHeader(body, REVIEW_SUMMARY, headerLines)) {
            return true;
        }
        for (String marker : APPEND_ONLY) {
            if (matchesHeader(body, marker, headerLines)) {
                return true;
            }
        }
        if (extraMarkers != null) {
            for (String marker : extraMarkers) {
                if (matchesHeader(body, marker, headerLines)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 用指定身份标记包装正文。 */
    public static String wrap(String marker, String body) {
        return marker + "\n\n" + (body == null ? "" : body);
    }

    /**
     * 移除正文中所有已知身份标记。
     *
     * <p>用于清洗模型输出：模型不应有能力在回复里伪造我们的身份标记，否则它可能把一条 Agent 回复
     * 伪装成审查摘要，从而在摘要更新时被命中覆盖。</p>
     *
     * @param extraMarkers 额外的标记，用于传入可配置的审查摘要标记
     */
    public static String stripIdentities(String body, String... extraMarkers) {
        if (!StringUtils.hasText(body)) {
            return body == null ? "" : body;
        }
        String result = body.replace(REVIEW_SUMMARY, "");
        for (String marker : APPEND_ONLY) {
            result = result.replace(marker, "");
        }
        if (extraMarkers != null) {
            for (String marker : extraMarkers) {
                if (StringUtils.hasText(marker)) {
                    result = result.replace(marker, "");
                }
            }
        }
        return result;
    }
}
