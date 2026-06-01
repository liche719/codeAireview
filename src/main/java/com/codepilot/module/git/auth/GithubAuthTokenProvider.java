package com.codepilot.module.git.auth;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.git.config.GithubProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
public class GithubAuthTokenProvider {

    private static final Duration APP_JWT_TTL = Duration.ofMinutes(9);

    private static final Duration APP_JWT_IAT_SKEW = Duration.ofSeconds(60);

    private final GithubProperties properties;

    private final RestClient restClient;

    private final ConcurrentMap<String, Long> installationIdByRepo = new ConcurrentHashMap<>();

    private final ConcurrentMap<Long, CachedInstallationToken> installationTokens = new ConcurrentHashMap<>();

    private volatile PrivateKey cachedPrivateKey;

    private volatile CachedLogin cachedLogin;

    @Autowired
    public GithubAuthTokenProvider(GithubProperties properties) {
        this(
                properties == null ? new GithubProperties() : properties,
                RestClient.builder()
                        .baseUrl(properties == null ? "https://api.github.com" : properties.getApiBaseUrl())
                        .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                        .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                        .build()
        );
    }

    public GithubAuthTokenProvider(GithubProperties properties, RestClient restClient) {
        this.properties = properties == null ? new GithubProperties() : properties;
        this.restClient = restClient;
    }

    public boolean canAuthenticate(String owner, String repo) {
        if (shouldUseGitHubApp()) {
            return isGitHubAppConfigured() && (hasConfiguredInstallationId() || hasText(owner) && hasText(repo));
        }
        return hasText(properties.getToken());
    }

    public Optional<String> resolveToken(String owner, String repo) {
        if (shouldUseGitHubApp()) {
            if (!canAuthenticate(owner, repo)) {
                return Optional.empty();
            }
            return Optional.of(resolveInstallationToken(owner, repo));
        }
        return hasText(properties.getToken()) ? Optional.of(properties.getToken().trim()) : Optional.empty();
    }

    public void setAuthorization(HttpHeaders headers, String owner, String repo) {
        resolveToken(owner, repo).ifPresent(headers::setBearerAuth);
    }

    public Optional<String> resolveAuthenticatedLogin() {
        if (shouldUseGitHubApp()) {
            return resolveGitHubAppBotLogin();
        }
        return resolvePatAuthenticatedLogin();
    }

    private boolean shouldUseGitHubApp() {
        GithubProperties.AuthMode mode = properties.getAuthMode() == null
                ? GithubProperties.AuthMode.AUTO
                : properties.getAuthMode();
        if (mode == GithubProperties.AuthMode.APP) {
            return true;
        }
        if (mode == GithubProperties.AuthMode.PAT) {
            return false;
        }
        return isGitHubAppConfigured();
    }

    private boolean isGitHubAppConfigured() {
        return hasText(properties.getAppId())
                && (hasText(properties.getAppPrivateKey()) || hasText(properties.getAppPrivateKeyBase64()));
    }

    private boolean hasConfiguredInstallationId() {
        return properties.getAppInstallationId() != null && properties.getAppInstallationId() > 0;
    }

    private String resolveInstallationToken(String owner, String repo) {
        long installationId = resolveInstallationId(owner, repo);
        CachedInstallationToken cached = installationTokens.get(installationId);
        if (cached != null && cached.isUsable(properties.getAppTokenCacheSkewSeconds())) {
            return cached.token();
        }
        synchronized (installationTokens) {
            cached = installationTokens.get(installationId);
            if (cached != null && cached.isUsable(properties.getAppTokenCacheSkewSeconds())) {
                return cached.token();
            }
            CachedInstallationToken refreshed = requestInstallationToken(installationId);
            installationTokens.put(installationId, refreshed);
            return refreshed.token();
        }
    }

    private long resolveInstallationId(String owner, String repo) {
        if (hasConfiguredInstallationId()) {
            return properties.getAppInstallationId();
        }
        if (!hasText(owner) || !hasText(repo)) {
            throw new BusinessException("GitHub App auth requires owner/repo when appInstallationId is not configured");
        }
        String key = normalize(owner) + "/" + normalize(repo);
        return installationIdByRepo.computeIfAbsent(key, ignored -> requestInstallationId(owner, repo));
    }

    private long requestInstallationId(String owner, String repo) {
        Map<String, Object> response = executeAuthRequest("failed to resolve GitHub App installation", () ->
                restClient.get()
                        .uri("/repos/{owner}/{repo}/installation", owner, repo)
                        .headers(headers -> headers.setBearerAuth(createAppJwt()))
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {
                        })
        );
        Object id = response == null ? null : response.get("id");
        Long installationId = longValue(id);
        if (installationId == null || installationId <= 0) {
            throw new BusinessException("failed to resolve GitHub App installation: missing installation id");
        }
        log.info("GitHub App installation resolved, owner={}, repo={}, installationId={}", owner, repo, installationId);
        return installationId;
    }

    private CachedInstallationToken requestInstallationToken(long installationId) {
        Map<String, Object> response = executeAuthRequest("failed to create GitHub App installation token", () ->
                restClient.post()
                        .uri("/app/installations/{installationId}/access_tokens", installationId)
                        .headers(headers -> headers.setBearerAuth(createAppJwt()))
                        .body(Map.of())
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {
                        })
        );
        String token = response == null || response.get("token") == null ? null : response.get("token").toString();
        String expiresAt = response == null || response.get("expires_at") == null ? null : response.get("expires_at").toString();
        if (!hasText(token) || !hasText(expiresAt)) {
            throw new BusinessException("failed to create GitHub App installation token: token response is incomplete");
        }
        Instant expires = Instant.parse(expiresAt);
        log.info("GitHub App installation token refreshed, installationId={}, expiresAt={}", installationId, expires);
        return new CachedInstallationToken(token, expires);
    }

    private Optional<String> resolveGitHubAppBotLogin() {
        CachedLogin cached = cachedLogin;
        if (cached != null && cached.isUsable()) {
            return Optional.of(cached.login());
        }
        synchronized (this) {
            cached = cachedLogin;
            if (cached != null && cached.isUsable()) {
                return Optional.of(cached.login());
            }
            Map<String, Object> response = executeAuthRequest("failed to resolve GitHub App identity", () ->
                    restClient.get()
                            .uri("/app")
                            .headers(headers -> headers.setBearerAuth(createAppJwt()))
                            .retrieve()
                            .body(new ParameterizedTypeReference<Map<String, Object>>() {
                            })
            );
            String slug = response == null || response.get("slug") == null ? null : response.get("slug").toString();
            if (!hasText(slug)) {
                return Optional.empty();
            }
            String login = slug.trim() + "[bot]";
            cachedLogin = new CachedLogin(login, Instant.now().plus(Duration.ofHours(1)));
            return Optional.of(login);
        }
    }

    private Optional<String> resolvePatAuthenticatedLogin() {
        if (!hasText(properties.getToken())) {
            return Optional.empty();
        }
        Map<String, Object> response = executeAuthRequest("failed to resolve GitHub authenticated user login", () ->
                restClient.get()
                        .uri("/user")
                        .headers(headers -> headers.setBearerAuth(properties.getToken().trim()))
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {
                        })
        );
        Object login = response == null ? null : response.get("login");
        return login == null || !hasText(login.toString())
                ? Optional.empty()
                : Optional.of(login.toString());
    }

    private String createAppJwt() {
        try {
            Instant now = Instant.now();
            String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = base64Url(("""
                    {"iat":%d,"exp":%d,"iss":"%s"}
                    """).formatted(
                    now.minus(APP_JWT_IAT_SKEW).getEpochSecond(),
                    now.plus(APP_JWT_TTL).getEpochSecond(),
                    properties.getAppId().trim()
            ).trim().getBytes(StandardCharsets.UTF_8));
            String signingInput = header + "." + payload;
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(resolvePrivateKey());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + base64Url(signature.sign());
        } catch (Exception exception) {
            throw new BusinessException("failed to create GitHub App JWT: " + SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }

    private PrivateKey resolvePrivateKey() throws Exception {
        PrivateKey privateKey = cachedPrivateKey;
        if (privateKey != null) {
            return privateKey;
        }
        synchronized (this) {
            if (cachedPrivateKey == null) {
                cachedPrivateKey = parsePrivateKey();
            }
            return cachedPrivateKey;
        }
    }

    private PrivateKey parsePrivateKey() throws Exception {
        String key = properties.getAppPrivateKey();
        byte[] rawDer = null;
        if (!hasText(key) && hasText(properties.getAppPrivateKeyBase64())) {
            byte[] decoded = Base64.getDecoder().decode(properties.getAppPrivateKeyBase64().trim());
            String decodedText = new String(decoded, StandardCharsets.UTF_8);
            if (decodedText.contains("BEGIN")) {
                key = decodedText;
            } else {
                rawDer = decoded;
            }
        }
        if (rawDer != null) {
            return generatePrivateKey(rawDer);
        }
        if (!hasText(key)) {
            throw new BusinessException("GitHub App private key is missing");
        }
        String normalized = key.replace("\\n", "\n").trim();
        if (normalized.contains("BEGIN RSA PRIVATE KEY")) {
            byte[] pkcs1 = decodePem(normalized, "RSA PRIVATE KEY");
            return generatePrivateKey(wrapPkcs1RsaPrivateKey(pkcs1));
        }
        if (normalized.contains("BEGIN PRIVATE KEY")) {
            return generatePrivateKey(decodePem(normalized, "PRIVATE KEY"));
        }
        return generatePrivateKey(Base64.getDecoder().decode(normalized));
    }

    private PrivateKey generatePrivateKey(byte[] der) throws Exception {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception exception) {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(wrapPkcs1RsaPrivateKey(der)));
        }
    }

    private byte[] decodePem(String pem, String type) {
        String content = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(content);
    }

    private byte[] wrapPkcs1RsaPrivateKey(byte[] pkcs1) {
        byte[] version = new byte[]{0x02, 0x01, 0x00};
        byte[] algorithmIdentifier = new byte[]{
                0x30, 0x0d,
                0x06, 0x09,
                0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
                0x05, 0x00
        };
        byte[] privateKey = derElement((byte) 0x04, pkcs1);
        return derElement((byte) 0x30, concat(version, algorithmIdentifier, privateKey));
    }

    private byte[] derElement(byte tag, byte[] value) {
        return concat(new byte[]{tag}, derLength(value.length), value);
    }

    private byte[] derLength(int length) {
        if (length < 128) {
            return new byte[]{(byte) length};
        }
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES).putInt(length);
        byte[] bytes = buffer.array();
        int firstNonZero = 0;
        while (firstNonZero < bytes.length && bytes[firstNonZero] == 0) {
            firstNonZero++;
        }
        int count = bytes.length - firstNonZero;
        byte[] result = new byte[count + 1];
        result[0] = (byte) (0x80 | count);
        System.arraycopy(bytes, firstNonZero, result, 1, count);
        return result;
    }

    private byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private <T> T executeAuthRequest(String operation, AuthRequestSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RestClientResponseException exception) {
            throw new BusinessException(operation + ": " + SensitiveDataSanitizer.redact(exception.getMessage()));
        } catch (RestClientException exception) {
            throw new BusinessException(operation + ": " + SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return StringUtils.hasText(value);
    }

    private record CachedInstallationToken(String token, Instant expiresAt) {

        boolean isUsable(long skewSeconds) {
            long safeSkew = Math.max(0L, skewSeconds);
            return hasText(token) && expiresAt != null && Instant.now().plusSeconds(safeSkew).isBefore(expiresAt);
        }
    }

    private record CachedLogin(String login, Instant expiresAt) {

        boolean isUsable() {
            return hasText(login) && expiresAt != null && Instant.now().isBefore(expiresAt);
        }
    }

    @FunctionalInterface
    private interface AuthRequestSupplier<T> {

        T get();
    }
}
