package com.codepilot.module.agent.review;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.infrastructure.llm.LlmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewLlmClientRegistry {

    private final LlmProperties llmProperties;

    private final ObjectProvider<ReviewLlmClient> reviewLlmClients;

    public Optional<ReviewLlmClient> select() {
        String provider = llmProperties.getProvider();
        List<ReviewLlmClient> matchingClients = reviewLlmClients.orderedStream()
                .filter(client -> client.supports(provider))
                .toList();
        if (matchingClients.isEmpty()) {
            log.warn("No review llm client supports configured provider={}",
                    SensitiveDataSanitizer.redact(provider));
            return Optional.empty();
        }
        if (matchingClients.size() > 1) {
            log.warn("Multiple review llm clients support provider={}, selectedClient={}, candidateCount={}",
                    SensitiveDataSanitizer.redact(provider),
                    matchingClients.getFirst().providerName(),
                    matchingClients.size());
        }
        return Optional.of(matchingClients.getFirst());
    }
}
