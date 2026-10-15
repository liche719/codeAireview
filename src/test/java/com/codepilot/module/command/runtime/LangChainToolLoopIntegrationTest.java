package com.codepilot.module.command.runtime;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LangChainToolLoopIntegrationTest {

    @Test
    void shouldExecuteMultipleModelSelectedToolsBeforeFinalResponse() {
        ScriptedChatModel model = new ScriptedChatModel();
        RecordingTools tools = new RecordingTools();
        TestAssistant assistant = AiServices.builder(TestAssistant.class)
                .chatModel(model)
                .tools(tools)
                .maxToolCallingRoundTrips(4)
                .maxSequentialToolsInvocations(4)
                .build();

        String response = assistant.chat("检查 Redis 规范并验证证据");

        assertThat(response).isEqualTo("已完成规则查询和证据验证");
        assertThat(tools.calls).containsExactly("lookup:Redis", "verify:rule:Redis");
        assertThat(model.turns.get()).isEqualTo(3);
    }

    interface TestAssistant {
        @UserMessage("{{request}}")
        String chat(@dev.langchain4j.service.V("request") String request);
    }

    static class RecordingTools {
        private final List<String> calls = new ArrayList<>();

        @Tool("Look up a team rule")
        public String lookup(@P("query") String query) {
            calls.add("lookup:" + query);
            return "rule:" + query;
        }

        @Tool("Verify retrieved evidence")
        public String verify(@P("evidence") String evidence) {
            calls.add("verify:" + evidence);
            return "verified";
        }
    }

    static class ScriptedChatModel implements ChatModel {
        private final AtomicInteger turns = new AtomicInteger();

        @Override
        public ChatResponse doChat(ChatRequest request) {
            int turn = turns.incrementAndGet();
            if (turn == 1) {
                return toolCall("call-1", "lookup", "{\"query\":\"Redis\"}");
            }
            if (turn == 2) {
                return toolCall("call-2", "verify", "{\"evidence\":\"rule:Redis\"}");
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("已完成规则查询和证据验证"))
                    .build();
        }

        private ChatResponse toolCall(String id, String name, String arguments) {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id(id)
                    .name(name)
                    .arguments(arguments)
                    .build();
            return ChatResponse.builder().aiMessage(AiMessage.from(request)).build();
        }
    }
}
