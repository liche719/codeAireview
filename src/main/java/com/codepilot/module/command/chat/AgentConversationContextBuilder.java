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

import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AgentConversationContextBuilder {

    private final PrCommandTaskMapper taskMapper;
    private final AgentRuntimeProperties properties;

    public String build(PrCommandTask currentTask) {
        if (currentTask == null || currentTask.getId() == null) {
            return "conversationContextStatus: UNAVAILABLE";
        }
        int maxTurns = Math.max(0, Math.min(properties.getConversationMaxTurns(), 20));
        if (maxTurns == 0) {
            return "conversationContextStatus: DISABLED";
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
            return "conversationContextStatus: EMPTY";
        }
        Collections.reverse(tasks);
        StringBuilder context = new StringBuilder("conversationContextStatus: AVAILABLE\n");
        for (PrCommandTask task : tasks) {
            if (!StringUtils.hasText(task.getCommandText()) || !StringUtils.hasText(task.getResponseBody())) {
                continue;
            }
            context.append("turn:\n  user=")
                    .append(oneLine(task.getCommandText()))
                    .append("\n  assistant=")
                    .append(oneLine(task.getResponseBody()))
                    .append('\n');
        }
        int maxChars = Math.max(500, properties.getConversationMaxChars());
        String result = context.toString();
        return result.length() <= maxChars ? result : result.substring(result.length() - maxChars);
    }

    private String oneLine(String value) {
        return PromptInputSanitizer.escapeUntrustedBlockDelimiters(value)
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
