package com.codepilot.module.command.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R8：硬上限的<b>用户可见语义</b>。
 *
 * <p>这些断言的核心不是"文案长什么样"，而是三条不能丢的信息：
 * 撞了哪个上限、上限是多少、接下来能做什么。原文案只说"请稍后重试"，
 * 用户唯一能做的就是原样再问一次然后再次撞墙。</p>
 */
class AgentLimitExceededExceptionTest {

    @Test
    void shouldNameTheLimitAndItsValue() {
        String message = new AgentLimitExceededException(
                AgentLimitExceededException.LimitType.TOOL_CALLS, 13, 12).userFacingMessage();

        assertThat(message)
                .contains("工具调用")
                .contains("12 次")
                .contains("不会自动重试");
    }

    /** 只告诉用户"超限了"没用，必须给出下一步可以怎么做。 */
    @Test
    void shouldTellTheUserWhatToDoNext() {
        for (AgentLimitExceededException.LimitType type : AgentLimitExceededException.LimitType.values()) {
            String message = new AgentLimitExceededException(type, 99, 10).userFacingMessage();

            assertThat(message)
                    .as("limit type %s must carry an actionable hint", type)
                    .contains("建议：")
                    .contains(type.hint());
        }
    }

    /** 运维需要能从回复直接定位到该调哪个配置项。 */
    @Test
    void shouldPointAtTheConfigKeySoOperatorsCanAct() {
        for (AgentLimitExceededException.LimitType type : AgentLimitExceededException.LimitType.values()) {
            assertThat(new AgentLimitExceededException(type, 99, 10).userFacingMessage())
                    .as("limit type %s must name its config key", type)
                    .contains(type.configKey());
        }
    }

    /** 四种上限的文案必须互不相同——否则"说清了原因"就无从谈起。 */
    @Test
    void shouldProduceDistinctMessagesPerLimitType() {
        String turns = new AgentLimitExceededException(
                AgentLimitExceededException.LimitType.MODEL_TURNS, 9, 8).userFacingMessage();
        String tools = new AgentLimitExceededException(
                AgentLimitExceededException.LimitType.TOOL_CALLS, 13, 12).userFacingMessage();
        String continuations = new AgentLimitExceededException(
                AgentLimitExceededException.LimitType.CONTINUATIONS, 5, 4).userFacingMessage();
        String deadline = new AgentLimitExceededException(
                AgentLimitExceededException.LimitType.RUN_DEADLINE, 1800, 1800).userFacingMessage();

        assertThat(turns).contains("模型轮次").contains("8 轮");
        assertThat(tools).contains("工具调用").contains("12 次");
        assertThat(continuations).contains("自动续跑次数").contains("4 次");
        assertThat(deadline).contains("单次运行时长").contains("30 分钟");
    }

    /** 整分钟要渲染成"分钟"，否则用户看到"1800 秒"还得自己换算。 */
    @Test
    void shouldHumanizeWholeMinuteDurations() {
        assertThat(AgentLimitExceededException.LimitType.RUN_DEADLINE.format(1800)).isEqualTo("30 分钟");
        assertThat(AgentLimitExceededException.LimitType.RUN_DEADLINE.format(3600)).isEqualTo("60 分钟");
        // 不是整分钟时保留秒，避免四舍五入把上限说小。
        assertThat(AgentLimitExceededException.LimitType.RUN_DEADLINE.format(90)).isEqualTo("90 秒");
        // 计数型上限不受影响。
        assertThat(AgentLimitExceededException.LimitType.TOOL_CALLS.format(12)).isEqualTo("12 次");
    }

    /** 异常本身仍要携带结构化数值，供日志与任务状态使用。 */
    @Test
    void shouldExposeStructuredValuesForLogging() {
        AgentLimitExceededException exception = new AgentLimitExceededException(
                AgentLimitExceededException.LimitType.MODEL_TURNS, 9, 8);

        assertThat(exception.getLimitType()).isEqualTo(AgentLimitExceededException.LimitType.MODEL_TURNS);
        assertThat(exception.getCurrentValue()).isEqualTo(9);
        assertThat(exception.getLimitValue()).isEqualTo(8);
        assertThat(exception.getMessage()).contains("MODEL_TURNS").contains("currentValue=9").contains("limitValue=8");
    }
}
