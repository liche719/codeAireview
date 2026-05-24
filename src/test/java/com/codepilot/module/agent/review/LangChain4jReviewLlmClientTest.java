package com.codepilot.module.agent.review;

import com.codepilot.module.agent.service.CodeReviewAiAssistant;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangChain4jReviewLlmClientTest {

    @Test
    void shouldDelegateReviewInputToLangChain4jAssistant() {
        CodeReviewAiAssistant assistant = mock(CodeReviewAiAssistant.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<CodeReviewAiAssistant> assistantProvider = mock(ObjectProvider.class);
        when(assistantProvider.getIfAvailable()).thenReturn(assistant);
        when(assistantProvider.getObject()).thenReturn(assistant);
        when(assistant.review("src/Demo.java", "+code", "rules", "files"))
                .thenReturn(new Result<>("{}", null, List.of(), null, List.of()));
        LangChain4jReviewLlmClient client = new LangChain4jReviewLlmClient(assistantProvider);

        String response = client.review(new ReviewLlmInput(
                "src/Demo.java",
                "+code",
                "rules",
                "files",
                false,
                false
        ));

        assertThat(client.providerName()).isEqualTo("langchain4j");
        assertThat(client.isAvailable()).isTrue();
        assertThat(response).isEqualTo("{}");
        verify(assistant).review("src/Demo.java", "+code", "rules", "files");
    }

    @Test
    void shouldReportUnavailableWhenAssistantBeanIsMissing() {
        @SuppressWarnings("unchecked")
        ObjectProvider<CodeReviewAiAssistant> assistantProvider = mock(ObjectProvider.class);
        when(assistantProvider.getIfAvailable()).thenReturn(null);
        LangChain4jReviewLlmClient client = new LangChain4jReviewLlmClient(assistantProvider);

        assertThat(client.isAvailable()).isFalse();
    }
}
