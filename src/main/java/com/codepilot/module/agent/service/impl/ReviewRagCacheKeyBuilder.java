package com.codepilot.module.agent.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

final class ReviewRagCacheKeyBuilder {

    private static final String CACHE_VERSION = "review-rag-v1";

    String build(String query, int topK, int maxContextChars, List<String> ruleTypes) {
        MessageDigest digest = sha256();
        appendDigest(digest, CACHE_VERSION);
        appendDigest(digest, query);
        appendDigest(digest, Integer.toString(topK));
        appendDigest(digest, Integer.toString(Math.max(0, maxContextChars)));
        appendDigest(digest, String.join(",", ruleTypes == null ? List.of() : ruleTypes));
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }

    private void appendDigest(MessageDigest digest, String value) {
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
