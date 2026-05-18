package com.codepilot.infrastructure.llm;

import com.codepilot.module.audit.entity.LlmCallLog;
import com.codepilot.module.audit.service.LlmCallLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final int RESPONSE_SUMMARY_LIMIT = 1000;

    private static final String SUPPORTED_PROVIDER = "openai-compatible";

    private final LlmProperties llmProperties;

    private final ObjectMapper objectMapper;

    private final LlmCallLogService llmCallLogService;

    @Override
    public LlmReviewResponse review(LlmReviewRequest request) {
        if (!SUPPORTED_PROVIDER.equalsIgnoreCase(llmProperties.getProvider())) {
            String errorMessage = "unsupported llm provider: " + llmProperties.getProvider();
            log.warn(errorMessage);
            return LlmReviewResponse.failure(llmProperties.getModel(), 0L, errorMessage);
        }

        long startTime = System.currentTimeMillis();
        String modelName = llmProperties.getModel();
        String responseBody = null;

        try {
            RestTemplate restTemplate = buildRestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(llmProperties.getApiKey());

            Map<String, Object> requestBody = Map.of(
                    "model", llmProperties.getModel(),
                    "temperature", llmProperties.getTemperature(),
                    "messages", List.of(
                            Map.of("role", "system", "content", "You are a strict code review assistant. Return JSON only."),
                            Map.of("role", "user", "content", request.getPrompt())
                    )
            );

            ResponseEntity<String> responseEntity = restTemplate.postForEntity(
                    buildChatCompletionsUrl(),
                    new HttpEntity<>(requestBody, headers),
                    String.class
            );
            responseBody = responseEntity.getBody();

            JsonNode rootNode = objectMapper.readTree(responseBody);
            String content = rootNode.path("choices").path(0).path("message").path("content").asText("");
            if (rootNode.hasNonNull("model")) {
                modelName = rootNode.path("model").asText(modelName);
            }

            long costTimeMs = System.currentTimeMillis() - startTime;
            saveCallLog(request, modelName, costTimeMs, true, null, content);
            return LlmReviewResponse.success(content, modelName, costTimeMs);
        } catch (RestClientException exception) {
            long costTimeMs = System.currentTimeMillis() - startTime;
            String errorMessage = exception.getMessage();
            log.warn("LLM request failed, filePath={}, message={}", request.getFilePath(), errorMessage);
            saveCallLog(request, modelName, costTimeMs, false, errorMessage, responseBody);
            return LlmReviewResponse.failure(modelName, costTimeMs, errorMessage);
        } catch (Exception exception) {
            long costTimeMs = System.currentTimeMillis() - startTime;
            String errorMessage = exception.getMessage();
            log.warn("LLM response parse failed, filePath={}, message={}", request.getFilePath(), errorMessage);
            saveCallLog(request, modelName, costTimeMs, false, errorMessage, responseBody);
            return LlmReviewResponse.failure(modelName, costTimeMs, errorMessage);
        }
    }

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(llmProperties.getTimeoutSeconds());
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return new RestTemplate(requestFactory);
    }

    private String buildChatCompletionsUrl() {
        String baseUrl = llmProperties.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            return baseUrl + "chat/completions";
        }
        return baseUrl + "/chat/completions";
    }

    private void saveCallLog(
            LlmReviewRequest request,
            String modelName,
            long costTimeMs,
            boolean success,
            String errorMessage,
            String responseSummary
    ) {
        try {
            LlmCallLog logRecord = new LlmCallLog();
            logRecord.setTaskId(request.getTaskId());
            logRecord.setModelName(modelName);
            logRecord.setCostTimeMs(costTimeMs);
            logRecord.setRequestSummary(buildRequestSummary(request));
            logRecord.setResponseSummary(truncate(responseSummary, RESPONSE_SUMMARY_LIMIT));
            logRecord.setSuccess(success);
            logRecord.setErrorMessage(errorMessage);
            logRecord.setCreatedAt(LocalDateTime.now());
            llmCallLogService.save(logRecord);
        } catch (Exception exception) {
            log.warn("Failed to save llm call log, taskId={}, filePath={}", request.getTaskId(), request.getFilePath(), exception);
        }
    }

    private String buildRequestSummary(LlmReviewRequest request) {
        return "filePath=" + request.getFilePath() + ", patchLength=" + request.getPatchLength();
    }

    private String truncate(String content, int maxLength) {
        if (!StringUtils.hasText(content) || content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength);
    }
}

