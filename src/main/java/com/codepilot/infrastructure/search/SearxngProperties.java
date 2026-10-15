package com.codepilot.infrastructure.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.search.searxng")
public class SearxngProperties {

    private boolean enabled = false;

    private String baseUrl = "";

    private Duration timeout = Duration.ofSeconds(5);

    private int maxResults = 5;

    private int maxQueryChars = 240;

    private int maxResultChars = 1200;

    private List<String> allowedDomains = List.of();

    private boolean documentFetchEnabled = false;

    private Duration documentFetchTimeout = Duration.ofSeconds(8);

    private int documentFetchMaxBytes = 262144;

    private int documentFetchMaxChars = 12000;
}
