package com.codepilot.infrastructure.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 给模型调用加"计数 + 限额"的装饰器（2026-10-29）。
 *
 * <p><b>为什么不是 ChatModelListener</b>：实测把 listener 挂到 chat model 上之后，
 * AiServices 内部的工具循环**并没有让计数增长**（`model_turn_count` 恒为 1），
 * 也就是 `max-model-turns` 看起来配了、实际永远不触发。装饰器没有这个问题——
 * 它是我们自己的类，每次模型调用必然经过 {@link #doChat(ChatRequest)}。
 *
 * <p><b>只在 agent 上下文里计数</b>：普通 PR 审查、规则问答等场景没有 runId，
 * 装饰器原样透传，不产生任何副作用。
 */
@Slf4j
@RequiredArgsConstructor
public class CountingChatModel implements ChatModel {

    private final ChatModel delegate;

    /** 用 ObjectProvider 延迟取：命令模块的 bean 不该成为配置层启动的硬依赖 */
    private final ObjectProvider<com.codepilot.module.command.runtime.AgentRunService> runServiceProvider;

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        Long runId = com.codepilot.module.command.trace.AgentTaskTraceContext.currentAgentRunId();
        if (runId != null) {
            com.codepilot.module.command.runtime.AgentRunService service = runServiceProvider.getIfAvailable();
            if (service != null) {
                // 撞上限时这里抛 AgentLimitExceededException，由运行时转成用户可见的收尾话术
                service.recordModelTurn(runId);
            }
        }
        return delegate.doChat(chatRequest);
    }

    @Override
    public dev.langchain4j.model.chat.request.ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public java.util.List<dev.langchain4j.model.chat.listener.ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public dev.langchain4j.model.ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public java.util.Set<dev.langchain4j.model.chat.Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }
}
