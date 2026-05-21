package com.codepilot.module.git.policy;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.git.config.GithubRepositoryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class GithubRepositoryPolicy {

    private final Set<String> allowedRepositories;

    public GithubRepositoryPolicy(GithubRepositoryProperties properties) {
        this.allowedRepositories = normalize(properties == null ? null : properties.getAllowedRepositories());
    }

    public boolean isRestricted() {
        return !allowedRepositories.isEmpty();
    }

    public void assertAllowed(String owner, String repo) {
        if (isAllowed(owner, repo)) {
            return;
        }
        String fullName = normalizeFullName(owner, repo);
        log.warn("GitHub repository rejected by allowlist, owner={}, repo={}", owner, repo);
        throw new BusinessException("GitHub repository is not allowed: " + fullName);
    }

    public boolean isAllowed(String owner, String repo) {
        return !isRestricted() || allowedRepositories.contains(normalizeFullName(owner, repo));
    }

    private Set<String> normalize(Iterable<String> repositories) {
        if (repositories == null) {
            return Set.of();
        }
        return java.util.stream.StreamSupport.stream(repositories.spliterator(), false)
                .filter(StringUtils::hasText)
                .map(this::normalizeRepository)
                .filter(StringUtils::hasText)
                .collect(Collectors.toUnmodifiableSet());
    }

    private String normalizeRepository(String repository) {
        String normalized = repository.trim()
                .replace('\\', '/')
                .toLowerCase(Locale.ROOT);
        if (normalized.startsWith("https://github.com/")) {
            normalized = normalized.substring("https://github.com/".length());
        }
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - ".git".length());
        }
        normalized = normalized.replaceAll("/+", "/");
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeFullName(String owner, String repo) {
        String ownerPart = StringUtils.hasText(owner) ? owner.trim() : "";
        String repoPart = StringUtils.hasText(repo) ? repo.trim() : "";
        return normalizeRepository(ownerPart + "/" + repoPart);
    }
}
