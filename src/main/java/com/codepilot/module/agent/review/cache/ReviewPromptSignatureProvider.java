package com.codepilot.module.agent.review.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Slf4j
@Component
public class ReviewPromptSignatureProvider {

    private static final String SYSTEM_PROMPT = "prompts/ai-review-system-message.txt";

    private static final String USER_PROMPT = "prompts/ai-review-user-message.txt";

    private final String signature;

    public ReviewPromptSignatureProvider() {
        this.signature = loadSignature();
    }

    public String signature() {
        return signature;
    }

    private String loadSignature() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            appendResource(digest, SYSTEM_PROMPT);
            appendResource(digest, USER_PROMPT);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            log.warn("Failed to build review prompt signature, cache will use fallback signature, errorType={}, message={}",
                    exception.getClass().getSimpleName(), exception.getMessage());
            return "prompt-signature-unavailable";
        }
    }

    private void appendResource(MessageDigest digest, String resourcePath) throws Exception {
        digest.update(resourcePath.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        try (InputStream inputStream = new ClassPathResource(resourcePath).getInputStream()) {
            digest.update(inputStream.readAllBytes());
        }
        digest.update((byte) 0);
    }
}
