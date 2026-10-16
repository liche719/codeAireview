package com.codepilot.module.command.runtime;

/**
 * Agent 运行触及某个<b>硬上限</b>时抛出。
 *
 * <h2>为什么要单独一个异常类型</h2>
 * 这些上限（模型轮次 / 工具调用 / 自动续跑 / 运行时长）原先都抛 {@code IllegalStateException}，
 * 与"LLM 超时""网络抖动"这类<b>可重试</b>故障混在一起，于是有两个后果：
 * <ol>
 *   <li>用户只看到通用文案「暂时无法完成本次 Agent 请求，请稍后重试」，
 *       不知道到底撞了哪个上限、更不知道该怎么办；</li>
 *   <li>失败被当成可重试处理，重试还会撞同一堵墙——白白消耗重试配额，
 *       只是把"拿到真实反馈"的时间往后拖。</li>
 * </ol>
 *
 * <p>单独成类之后，调用方可以：吐出一条<b>说清原因且可行动</b>的回复，并把它标成终态不再重试。</p>
 *
 * <h2>刻意不做的两件事</h2>
 * <ul>
 *   <li><b>不去提高限额。</b>限额是成本与失控保护，不是 bug。</li>
 *   <li><b>不把这个类型用于"看起来像限额"的其他异常。</b>只有四个明确的上限才走终态；
 *       真正的可重试故障（LLM 超时、GitHub 5xx）必须留在可重试路径上，
 *       否则会把"等一下就好"的问题变成永久失败。</li>
 * </ul>
 */
public class AgentLimitExceededException extends RuntimeException {

    /**
     * 四种硬上限。{@code configKey} 让用户/运维能直接找到该调哪个配置项，
     * {@code hint} 是"那我能怎么办"的下一步建议。
     */
    public enum LimitType {

        MODEL_TURNS("模型轮次", "轮", "codepilot.agent-runtime.max-model-turns",
                "把问题拆成更小的几步再问"),

        TOOL_CALLS("工具调用", "次", "codepilot.agent-runtime.max-tool-calls",
                "把问题拆小，或直接指明要看哪个文件、哪段逻辑"),

        CONTINUATIONS("自动续跑次数", "次", "codepilot.agent-runtime.max-continuations",
                "等上一次审查流程跑完后再问"),

        RUN_DEADLINE("单次运行时长", "秒", "codepilot.agent-runtime.run-deadline",
                "缩小问题范围后重试");

        private final String displayName;

        private final String unitLabel;

        private final String configKey;

        private final String hint;

        LimitType(String displayName, String unitLabel, String configKey, String hint) {
            this.displayName = displayName;
            this.unitLabel = unitLabel;
            this.configKey = configKey;
            this.hint = hint;
        }

        public String displayName() {
            return displayName;
        }

        public String configKey() {
            return configKey;
        }

        public String hint() {
            return hint;
        }

        /** 把数值渲染成带单位的可读形式。 */
        public String format(int value) {
            if (this == RUN_DEADLINE && value > 0 && value % 60 == 0) {
                return (value / 60) + " 分钟";
            }
            return value + " " + unitLabel;
        }
    }

    private final LimitType limitType;

    private final int currentValue;

    private final int limitValue;

    public AgentLimitExceededException(LimitType limitType, int currentValue, int limitValue) {
        super("Agent limit exceeded: type=" + limitType.name()
                + ", currentValue=" + currentValue + ", limitValue=" + limitValue);
        this.limitType = limitType;
        this.currentValue = currentValue;
        this.limitValue = limitValue;
    }

    public LimitType getLimitType() {
        return limitType;
    }

    public int getCurrentValue() {
        return currentValue;
    }

    public int getLimitValue() {
        return limitValue;
    }

    /**
     * 面向用户的回复文案。
     *
     * <p>三件事必须说清：<b>撞了哪个上限</b>、<b>上限是多少</b>、<b>接下来怎么办</b>。
     * 最后再明确一句"不会自动重试"，避免用户干等一个永远不会来的重试。</p>
     */
    public String userFacingMessage() {
        return "**CodePilot AI** 本次请求已达到「" + limitType.displayName() + "」上限（"
                + limitType.format(limitValue) + "），已停止执行。\n\n"
                + "建议：" + limitType.hint() + "。\n\n"
                + "本次<b>不会自动重试</b>（重试仍会撞同一上限）。如确需调整，请联系维护者修改 `"
                + limitType.configKey() + "`。";
    }
}
