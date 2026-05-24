package com.codepilot.module.agent.review.cache;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.agent.review.ReviewLlmInput;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
@RequiredArgsConstructor
public class ReviewLlmCacheKeyBuilder {

    private static final String CACHE_VERSION = "ai-review-cache-v1";

    private final LlmProperties llmProperties;

    private final ReviewPromptSignatureProvider reviewPromptSignatureProvider;

    public ReviewLlmCacheKey build(String providerName, ReviewLlmInput input) {
        String provider = firstText(providerName, llmProperties.getProvider());
        String modelName = safeText(llmProperties.getModel());
        String promptSignature = reviewPromptSignatureProvider.signature();

        MessageDigest digest = sha256();
        append(digest, CACHE_VERSION);
        append(digest, provider);
        append(digest, llmProperties.getBaseUrl());
        append(digest, modelName);
        append(digest, Double.toString(llmProperties.getTemperature()));
        append(digest, promptSignature);
        append(digest, input == null ? null : input.filePath());
        append(digest, input == null ? null : input.patch());
        append(digest, input == null ? null : input.rulesContext());
        append(digest, input == null ? null : input.changedFilesContext());
        append(digest, input == null ? null : Boolean.toString(input.truncatedRulesContext()));
        append(digest, input == null ? null : Boolean.toString(input.truncatedChangedFilesContext()));

        return new ReviewLlmCacheKey(
                HexFormat.of().formatHex(digest.digest()),
                provider,
                modelName,
                promptSignature
        );
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }

    private void append(MessageDigest digest, String value) {
        digest.update(safeText(value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private String firstText(String first, String fallback) {
        return StringUtils.hasText(first) ? first.trim() : safeText(fallback);
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
