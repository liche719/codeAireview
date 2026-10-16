package com.codepilot.module.command.chat;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.codepilot.module.command.config.AgentRuntimeProperties;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R9：对话上下文按 <b>turn 边界</b>裁剪，而不是盲截尾部字符。
 *
 * <p>这个类里最要紧的断言是"不出现半句"。判定方式不是比对字符串长度，
 * 而是检查每条 turn 都同时含 {@code user=} 与 {@code assistant=}——
 * 原实现会把消息切成半句，甚至切出一条没有 {@code assistant=} 的残缺 turn。</p>
 */
class AgentConversationContextBuilderTest {

    private static final String STATUS_PREFIX = "conversationContextStatus: ";

    @Test
    void shouldReturnUnavailableWithoutTaskIdentity() {
        assertThat(builder(new AgentRuntimeProperties(), List.of()).build(null))
                .isEqualTo(STATUS_PREFIX + "UNAVAILABLE");
    }

    @Test
    void shouldReportDisabledWhenTurnsAreZero() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setConversationMaxTurns(0);

        assertThat(builder(properties, List.of(chatTask(1, "q", "a"))).build(currentTask()))
                .isEqualTo(STATUS_PREFIX + "DISABLED");
    }

    @Test
    void shouldReportEmptyWhenThereIsNoHistory() {
        assertThat(builder(new AgentRuntimeProperties(), List.of()).build(currentTask()))
                .isEqualTo(STATUS_PREFIX + "EMPTY");
    }

    @Test
    void shouldKeepEveryTurnWhenItAllFits() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        List<PrCommandTask> history = List.of(
                chatTask(3, "q3", "a3"), chatTask(2, "q2", "a2"), chatTask(1, "q1", "a1"));

        String context = builder(properties, history).build(currentTask());

        assertThat(context).startsWith(STATUS_PREFIX + "AVAILABLE");
        assertThat(context).contains("user=q1").contains("assistant=a1");
        assertThat(context).contains("user=q3").contains("assistant=a3");
        assertThat(turnCount(context)).isEqualTo(3);
        assertThat(context).doesNotContain("truncated");
    }

    /** 最旧的 turn 先被丢，且必须明确标注 PARTIAL 与保留了几条。 */
    @Test
    void shouldDropOldestTurnsAndReportPartial() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setConversationMaxChars(500);
        List<PrCommandTask> history = history(5, 50, 50);

        String context = builder(properties, history).build(currentTask());

        assertThat(context).startsWith(STATUS_PREFIX + "PARTIAL (3 of 5 turns)");
        assertThat(turnCount(context)).isEqualTo(3);
        // 最近三条留下，最旧两条被丢弃。
        assertThat(context).contains("user=q5").contains("user=q3");
        assertThat(context).doesNotContain("user=q2").doesNotContain("user=q1");
    }

    /**
     * 核心回归：按 turn 裁剪后，每条留下的 turn 都必须是完整的。
     *
     * <p>这正是不含边界感知的"盲截尾部字符"会破坏的性质。注意判定不能只看
     * "被 {@code turn:} 分开的每一段是否完整"——那样会把<b>开头那段残缺内容</b>漏掉，
     * 于是旧实现也能通过。必须额外检查第一条 turn 之前只有状态行。</p>
     */
    @Test
    void shouldNeverEmitAHalfTurn() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setConversationMaxChars(500);

        String context = builder(properties, history(5, 50, 50)).build(currentTask());

        for (String turn : turns(context)) {
            assertThat(turn)
                    .as("every kept turn must carry both halves")
                    .contains("  user=")
                    .contains("  assistant=");
        }
        // 未被标记截断时，assistant 内容必须是原文，而不是前缀。
        assertThat(context).contains("assistant=a3" + "y".repeat(50));
        // 第一条 turn 之前只能是状态行，不能是半条被切碎的消息。
        assertThat(preamble(context)).isEqualTo(STATUS_PREFIX + "PARTIAL (3 of 5 turns)\n");
        // 正常路径下最后一条 turn 也必须是完整的（以换行收尾）。
        assertThat(context).endsWith("\n");
    }

    /**
     * 单独把"开头不能有孤儿片段"钉成一条断言。
     *
     * <p>盲截尾部字符时，切口通常落在某条 turn 的中间，于是输出以半句话开头——
     * 而按 {@code turn:\n} 切分会把这段孤儿片段丢掉，看起来"每条 turn 都完整"。</p>
     */
    @Test
    void shouldNotLeaveAnOrphanFragmentBeforeTheFirstTurn() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setConversationMaxChars(500);

        String context = builder(properties, history(5, 50, 50)).build(currentTask());

        assertThat(preamble(context))
                .matches(STATUS_PREFIX + "(AVAILABLE|PARTIAL \\(\\d+ of \\d+ turns\\))\\n");
    }

    /** 只有"最新一条单独超预算"时才截断，且必须打显式标记、保留 turn 结构。 */
    @Test
    void shouldMarkAndKeepStructureWhenTheNewestTurnAloneIsTooBig() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setConversationMaxChars(500);
        List<PrCommandTask> history = List.of(chatTask(1, "q".repeat(50), "a".repeat(2000)));

        String context = builder(properties, history).build(currentTask());

        assertThat(context).contains("truncated");
        assertThat(context).contains("  user=").contains("  assistant=");
        assertThat(context).startsWith(STATUS_PREFIX + "PARTIAL (1 of 1 turns)");
        assertThat(context.length()).isLessThanOrEqualTo(500);
    }

    /** 状态行永远在最前面——下游正是靠它判断上下文可用性。 */
    @Test
    void shouldNeverTruncateAwayTheStatusLine() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setConversationMaxChars(500);

        for (List<PrCommandTask> history : List.of(
                history(5, 50, 50),
                List.of(chatTask(1, "q".repeat(50), "a".repeat(2000))),
                List.of(chatTask(1, "q".repeat(4000), "a".repeat(4000))))) {
            String context = builder(properties, history).build(currentTask());
            assertThat(context).startsWith(STATUS_PREFIX);
            assertThat(context.length()).isLessThanOrEqualTo(500);
        }
    }

    /** 无论怎么裁，都不能超过配置的字符上限。 */
    @Test
    void shouldNeverExceedTheCharBudget() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setConversationMaxChars(500);

        for (int count = 1; count <= 20; count++) {
            String context = builder(properties, history(count, 40, 400)).build(currentTask());
            assertThat(context.length())
                    .as("turn count %s", count)
                    .isLessThanOrEqualTo(500);
        }
    }

    /** user 那一半本身就巨大时也不能切掉 assistant 段。 */
    @Test
    void shouldKeepStructureWhenTheUserHalfAloneIsTooBig() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setConversationMaxChars(500);
        List<PrCommandTask> history = List.of(chatTask(1, "u".repeat(5000), "a".repeat(5000)));

        String context = builder(properties, history).build(currentTask());

        assertThat(context).contains("  user=").contains("  assistant=").contains("truncated");
        assertThat(context.length()).isLessThanOrEqualTo(500);
    }

    /**
     * 回滚开关：{@code chars} 模式必须复现引入 R9 之前的旧行为。
     *
     * <p>这里刻意断言旧行为的<b>缺陷也一并保留</b>（状态行会被截掉），
     * 否则"回滚开关"名不副实——有人按文档回滚却发现行为跟以前不一样，会更糟。</p>
     */
    @Test
    void shouldReproduceLegacyBlindTruncationInCharsMode() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setConversationMaxChars(500);
        properties.setConversationTruncationMode(
                AgentRuntimeProperties.ConversationTruncationMode.CHARS);

        String context = builder(properties, history(5, 50, 50)).build(currentTask());

        assertThat(context).hasSize(500);
        assertThat(context).doesNotStartWith(STATUS_PREFIX);
    }

    /** 跳过 user/assistant 缺一半的历史记录：它们构不成一条 turn。 */
    @Test
    void shouldSkipRecordsMissingEitherHalf() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        List<PrCommandTask> history = new ArrayList<>();
        history.add(chatTask(3, "q3", "a3"));
        history.add(chatTask(2, "q2", null));
        history.add(chatTask(1, "  ", "a1"));

        String context = builder(properties, history).build(currentTask());

        assertThat(context).startsWith(STATUS_PREFIX + "AVAILABLE");
        assertThat(context).contains("user=q3");
        assertThat(context).doesNotContain("q2").doesNotContain("a1");
    }

    // ------------------------------------------------------------------

    private AgentConversationContextBuilder builder(AgentRuntimeProperties properties, List<PrCommandTask> history) {
        PrCommandTaskMapper mapper = mock(PrCommandTaskMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>(history));
        return new AgentConversationContextBuilder(mapper, properties);
    }

    /** 按 mapper 的排序约定返回：id 递减（最近的在最前）。 */
    private static List<PrCommandTask> history(int count, int userLength, int assistantLength) {
        List<PrCommandTask> history = new ArrayList<>();
        for (int index = count; index >= 1; index--) {
            // 填充用字母而不是空格：oneLine() 会把连续空白折叠掉，
            // 用空格做填充等于没有任何长度，测试就测不到裁剪路径了。
            history.add(chatTask(
                    index,
                    "q" + index + "x".repeat(userLength),
                    "a" + index + "y".repeat(assistantLength)));
        }
        return history;
    }

    private static PrCommandTask chatTask(int id, String commandText, String responseBody) {
        PrCommandTask task = new PrCommandTask();
        task.setId((long) id);
        task.setCommandType("CHAT");
        task.setStatus("SUCCESS");
        task.setCommandText(commandText);
        task.setResponseBody(responseBody);
        return task;
    }

    private static PrCommandTask currentTask() {
        PrCommandTask task = new PrCommandTask();
        task.setId(99L);
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(12);
        return task;
    }

    private static List<String> turns(String context) {
        List<String> turns = new ArrayList<>();
        for (String part : context.split("turn:\n")) {
            if (part.contains("  user=")) {
                turns.add(part);
            }
        }
        return turns;
    }

    private static int turnCount(String context) {
        return turns(context).size();
    }

    /**
     * 第一条 {@code turn:} 之前的内容。
     *
     * <p>正常只应是状态行；盲截模式下这里会出现被切碎的消息残片。</p>
     */
    private static String preamble(String context) {
        int index = context.indexOf("turn:\n");
        return index < 0 ? context : context.substring(0, index);
    }
}
