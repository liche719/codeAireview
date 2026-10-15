package com.codepilot.module.dlq.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.dlq")
public class DeadLetterProperties {

    /**
     * 是否消费 DLQ 并把死信落库。
     *
     * <p>落库本身没有副作用，默认开启——否则消息进了 DLQ 就等于丢失。</p>
     */
    private boolean consumerEnabled = true;

    /**
     * 是否开放死信重放接口。**默认关闭**：重放会把消息重新投回主链路，
     * 属于有副作用的运维动作，必须显式开启。
     */
    private boolean replayEnabled = false;

    /** 死信记录保留天数，由清理任务使用。 */
    private int retentionDays = 30;

    /** 落库前 payload 的最大字符数（超出截断，避免把大消息塞进数据库）。 */
    private int payloadMaxChars = 4000;

    /** 重放时记录的操作者标识，用于审计。 */
    private String replayOperator = "api";
}
