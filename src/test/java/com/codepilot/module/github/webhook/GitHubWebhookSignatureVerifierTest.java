package com.codepilot.module.github.webhook;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubWebhookSignatureVerifierTest {

    @Test
    void shouldPassWhenSignatureIsCorrect() throws Exception {
        String secret = "test-secret";
        String payload = "{\"action\":\"opened\"}";
        GitHubWebhookSignatureVerifier verifier = new GitHubWebhookSignatureVerifier(secret);

        assertThat(verifier.verify(payload, "sha256=" + hmacSha256Hex(secret, payload))).isTrue();
    }

    @Test
    void shouldFailWhenSignatureIsWrong() {
        GitHubWebhookSignatureVerifier verifier = new GitHubWebhookSignatureVerifier("test-secret");

        assertThat(verifier.verify("{\"action\":\"opened\"}", "sha256=wrong")).isFalse();
    }

    @Test
    void shouldPassWhenSecretIsEmptyForLocalDevelopment() {
        GitHubWebhookSignatureVerifier verifier = new GitHubWebhookSignatureVerifier("");

        assertThat(verifier.verify("{\"action\":\"opened\"}", null)).isTrue();
    }

    private String hmacSha256Hex(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
