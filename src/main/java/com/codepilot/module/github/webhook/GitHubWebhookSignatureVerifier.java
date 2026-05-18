package com.codepilot.module.github.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Slf4j
@Component
public class GitHubWebhookSignatureVerifier {

    private static final String SIGNATURE_PREFIX = "sha256=";

    private final String webhookSecret;

    private final boolean skipSignatureWhenSecretEmpty;

    public GitHubWebhookSignatureVerifier(
            @Value("${codepilot.github.webhook-secret:}") String webhookSecret,
            @Value("${codepilot.github.webhook-skip-signature-when-secret-empty:false}") boolean skipSignatureWhenSecretEmpty
    ) {
        this.webhookSecret = webhookSecret;
        this.skipSignatureWhenSecretEmpty = skipSignatureWhenSecretEmpty;
    }

    public boolean verify(String payload, String signatureHeader) {
        if (!StringUtils.hasText(webhookSecret)) {
            if (skipSignatureWhenSecretEmpty) {
                log.warn("GitHub webhook signature verification skipped because webhook secret is empty");
                return true;
            }
            log.warn("GitHub webhook signature verification failed because webhook secret is empty");
            return false;
        }
        if (!StringUtils.hasText(signatureHeader) || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }

        String expectedSignature = SIGNATURE_PREFIX + hmacSha256Hex(payload == null ? "" : payload);
        return MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String hmacSha256Hex(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to calculate GitHub webhook signature", exception);
        }
    }
}
