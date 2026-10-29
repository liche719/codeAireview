package com.codepilot.module.command.trace;

import com.codepilot.module.command.runtime.AgentRunService;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把"每一次真实的模型调用"计入 agent_run（2026-10-29）。
 *
 * <p>挂到 {@code codeReviewChatModel} 上。只有在 agent 任务上下文里（有 runId）才计数，
 * 普通审查或其它场景一律跳过——监听器不该变成全局副作用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentModelTurnListener implements ChatModelListener {

    private final AgentRunService agentRunService;

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        Long runId = AgentTaskTraceContext.currentAgentRunId();
        if (runId == null) {
            return;
        }
        try {
            agentRunService.recordModelTurn(runId);
        } catch (RuntimeException exception) {
            // 这里**必须往上抛**：撞上限是要让运行时收尾的，吞掉就等于限额失效。
            throw exception;
        }
    }
}
