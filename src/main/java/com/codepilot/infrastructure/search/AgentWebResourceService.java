package com.codepilot.infrastructure.search;

import com.codepilot.module.command.mapper.AgentWebResourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AgentWebResourceService {

    private static final Pattern RESULT_URL = Pattern.compile("(?m)^\\s*url=(https://\\S+)\\s*$");

    private final AgentWebResourceMapper mapper;

    public void registerSearchResults(Long runId, String searchResult) {
        if (runId == null || !StringUtils.hasText(searchResult)) {
            return;
        }
        Matcher matcher = RESULT_URL.matcher(searchResult);
        while (matcher.find()) {
            String url = matcher.group(1).trim();
            mapper.register(runId, hash(url), url, LocalDateTime.now());
        }
    }

    public boolean isRegistered(Long runId, String url) {
        return runId != null && StringUtils.hasText(url)
                && mapper.existsForRun(runId, hash(url), url) > 0;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash web resource URL", exception);
        }
    }
}
