package com.codepilot.module.git.auth;

import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

class GithubAppJwtFactory {

    private static final Duration APP_JWT_TTL = Duration.ofMinutes(9);

    private static final Duration APP_JWT_IAT_SKEW = Duration.ofSeconds(60);

    String create(String appId, PrivateKey privateKey) throws Exception {
        if (!StringUtils.hasText(appId)) {
            throw new IllegalArgumentException("GitHub App id is missing");
        }
        if (privateKey == null) {
            throw new IllegalArgumentException("GitHub App private key is missing");
        }
        Instant now = Instant.now();
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = base64Url(("""
                {"iat":%d,"exp":%d,"iss":"%s"}
                """).formatted(
                now.minus(APP_JWT_IAT_SKEW).getEpochSecond(),
                now.plus(APP_JWT_TTL).getEpochSecond(),
                appId.trim()
        ).trim().getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + base64Url(signature.sign());
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
