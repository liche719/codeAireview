package com.codepilot.module.command.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.agent-runtime")
public class AgentRuntimeProperties {

    private boolean resumableEnabled = true;

    private int maxModelTurns = 8;

    private int maxToolCalls = 12;

    private int maxContinuations = 4;

    private Duration runDeadline = Duration.ofMinutes(30);

    private int continuationContextMaxChars = 4000;

    private int waitingPollBatchSize = 50;

    private int conversationMaxTurns = 5;

    private int conversationMaxChars = 6000;

    /**
     * 对话上下文的裁剪方式（R9）。
     *
     * <p>{@link ConversationTruncationMode#TURN}（默认）按<b>整条 turn</b> 裁剪，
     * 宁可少留几条也不把一条消息切成半句；{@link ConversationTruncationMode#CHARS}
     * 是引入本能力之前的盲截尾部字符行为，保留作为回滚开关。</p>
     */
    private ConversationTruncationMode conversationTruncationMode = ConversationTruncationMode.TURN;

    /** 对话上下文的裁剪方式。 */
    public enum ConversationTruncationMode {
        TURN,
        CHARS
    }
}
