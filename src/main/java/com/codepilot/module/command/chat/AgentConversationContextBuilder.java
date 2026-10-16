package com.codepilot.module.command.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.common.util.PromptInputSanitizer;
import com.codepilot.module.command.config.AgentRuntimeProperties;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.entity.PrCommandTaskType;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 把同一 PR 上最近几条成功的 CHAT 记录拼成对话上下文。
 *
 * <h2>为什么要按 turn 裁剪（R9）</h2>
 * 原实现超预算时做 {@code result.substring(result.length() - maxChars)}——盲截尾部字符。
 * 三个后果：
 * <ol>
 *   <li><b>把一条消息切成半句</b>，模型看到的是断掉的句子；</li>
 *   <li>更糟的是可能切成 {@code "  user=……"} 后面没有 {@code assistant=}，
 *       留下一条结构上讲不通的 turn；</li>
 *   <li>开头的 {@code conversationContextStatus:} 会被一起截掉——
 *       而下游正是靠这行判断上下文是否可用。</li>
 * </ol>
 *
 * <p>现在的规则是：<b>从最近往前累加整条 turn</b>，装不下就停；
 * 只有"最新那一条单独就超预算"时才截断它，且必须打显式 {@code …[truncated]} 标记。
 * 状态行永远保留在最前面。代价是同样字符预算下能容纳的信息更少——
 * 这是刻意的取舍：<b>完整性优先于信息量</b>。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentConversationContextBuilder {

    private static final String STATUS_PREFIX = "conversationContextStatus: ";

    private static final String TRUNCATION_MARKER = "…[truncated]";

    private static final String TURN_PREFIX = "turn:\n  user=";

    private static final String TURN_MIDDLE = "\n  assistant=";

    /**
     * 给状态行预留的字符数。
     *
     * <p>状态行长度取决于 turn 数量（{@code PARTIAL (20 of 20 turns)} 是最长形态，
     * 因为 {@code conversation-max-turns} 运行时被压到 20），固定预留足以覆盖。</p>
     */
    private static final int STATUS_RESERVE = 64;

    /** 单条超大 turn 里，user 那一半最多占可用空间的这个比例。 */
    private static final int OVERSIZED_USER_SHARE = 3;

    private final PrCommandTaskMapper taskMapper;

    private final AgentRuntimeProperties properties;

    public String build(PrCommandTask currentTask) {
        if (currentTask == null || currentTask.getId() == null) {
            return STATUS_PREFIX + "UNAVAILABLE";
        }
        int maxTurns = Math.max(0, Math.min(properties.getConversationMaxTurns(), 20));
        if (maxTurns == 0) {
            return STATUS_PREFIX + "DISABLED";
        }
        List<PrCommandTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<PrCommandTask>()
                .eq(PrCommandTask::getCommandType, PrCommandTaskType.CHAT)
                .eq(PrCommandTask::getStatus, "SUCCESS")
                .eq(PrCommandTask::getRepoOwner, currentTask.getRepoOwner())
                .eq(PrCommandTask::getRepoName, currentTask.getRepoName())
                .eq(PrCommandTask::getPrNumber, currentTask.getPrNumber())
                .ne(PrCommandTask::getId, currentTask.getId())
                .orderByDesc(PrCommandTask::getId)
                .last("LIMIT " + maxTurns));
        if (tasks == null || tasks.isEmpty()) {
            return STATUS_PREFIX + "EMPTY";
        }
        Collections.reverse(tasks);

        List<PrCommandTask> usableTurns = new ArrayList<>();
        for (PrCommandTask task : tasks) {
            if (StringUtils.hasText(task.getCommandText()) && StringUtils.hasText(task.getResponseBody())) {
                usableTurns.add(task);
            }
        }
        if (usableTurns.isEmpty()) {
            return STATUS_PREFIX + "EMPTY";
        }

        int maxChars = Math.max(500, properties.getConversationMaxChars());
        if (properties.getConversationTruncationMode() == AgentRuntimeProperties.ConversationTruncationMode.CHARS) {
            return legacyCharTruncated(usableTurns, maxChars);
        }
        return turnBounded(usableTurns, maxChars);
    }

    private String turnBounded(List<PrCommandTask> turns, int maxChars) {
        int turnBudget = Math.max(0, maxChars - STATUS_RESERVE);

        List<String> selected = new ArrayList<>();
        int used = 0;
        boolean truncated = false;
        for (int index = turns.size() - 1; index >= 0; index--) {
            String rendered = renderTurn(turns.get(index));
            if (used + rendered.length() <= turnBudget) {
                selected.add(0, rendered);
                used += rendered.length();
                continue;
            }
            if (selected.isEmpty()) {
                // 最新一条单独就超预算。宁可截断它并明确标记，也不要整段丢掉——
                // 多轮追问里最新一条恰恰是模型最需要的。
                selected.add(0, renderOversizedTurn(turns.get(index), turnBudget));
                truncated = true;
            }
            // 更旧的一律丢弃：装不下的情况下，越旧越不值得留。
            break;
        }

        boolean partial = truncated || selected.size() < turns.size();
        String header = partial
                ? STATUS_PREFIX + "PARTIAL (" + selected.size() + " of " + turns.size() + " turns)"
                : STATUS_PREFIX + "AVAILABLE";

        StringBuilder context = new StringBuilder(header).append('\n');
        for (String turn : selected) {
            context.append(turn);
        }
        return enforceBudget(context.toString(), maxChars);
    }

    /**
     * 单条 turn 超预算时的渲染：保留 {@code turn / user / assistant} 结构，
     * 只把两侧正文按比例截短。
     *
     * <p>为什么不直接截字符串：那样会切掉 {@code assistant=} 这一段，
     * 留下的是一条缺了回答的 turn——正是本次要修的毛病。</p>
     */
    private String renderOversizedTurn(PrCommandTask task, int budget) {
        int fixed = TURN_PREFIX.length() + TURN_MIDDLE.length() + 1 + TRUNCATION_MARKER.length();
        int room = Math.max(0, budget - fixed);
        String user = oneLine(task.getCommandText());
        String assistant = oneLine(task.getResponseBody());
        int userRoom = Math.min(user.length(), Math.max(1, room / OVERSIZED_USER_SHARE));
        String keptUser = trimTo(user, userRoom);
        String keptAssistant = trimTo(assistant, Math.max(0, room - keptUser.length()));
        return TURN_PREFIX + keptUser + TURN_MIDDLE + keptAssistant + TRUNCATION_MARKER + "\n";
    }

    /**
     * 引入 R9 之前的旧行为：盲截尾部字符。
     *
     * <p>保留它是为了 {@code conversation-truncation-mode=chars} 能一键回滚。
     * <b>它会把消息切成半句</b>，所以只应作为兼容开关存在。</p>
     */
    private String legacyCharTruncated(List<PrCommandTask> turns, int maxChars) {
        StringBuilder context = new StringBuilder(STATUS_PREFIX).append("AVAILABLE\n");
        for (PrCommandTask task : turns) {
            context.append(renderTurn(task));
        }
        String result = context.toString();
        return result.length() <= maxChars ? result : result.substring(result.length() - maxChars);
    }

    private String renderTurn(PrCommandTask task) {
        return TURN_PREFIX
                + oneLine(task.getCommandText())
                + TURN_MIDDLE
                + oneLine(task.getResponseBody())
                + '\n';
    }

    /**
     * 兜底：正常情况下预算计算已经保证不超，这里只是防御性收口，
     * 保证任何输入下都不会返回超过 {@code maxChars} 的字符串。
     */
    private String enforceBudget(String context, int maxChars) {
        if (context.length() <= maxChars) {
            return context;
        }
        int keep = Math.max(0, maxChars - TRUNCATION_MARKER.length());
        return context.substring(0, keep) + TRUNCATION_MARKER;
    }

    private String trimTo(String value, int limit) {
        if (limit <= 0) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private String oneLine(String value) {
        return PromptInputSanitizer.escapeUntrustedBlockDelimiters(value)
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
