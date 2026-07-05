package com.codepilot.module.git.client;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.git.dto.GithubLinkedIssue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
class GithubLinkedIssueResolver {

    static final int MAX_LINKED_ISSUES = 20;

    private static final Pattern CLOSING_KEYWORD_LINE = Pattern.compile(
            "(?i)\\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\b[^\\r\\n]*"
    );

    private static final Pattern ISSUE_REFERENCE = Pattern.compile(
            "(?i)(?:https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/issues/(\\d+)"
                    + "|([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)#(\\d+)"
                    + "|#(\\d+))"
    );

    List<GithubLinkedIssue> fromGraphqlResponse(
            String owner,
            String repo,
            Map<String, Object> response
    ) {
        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return List.of();
        }
        Object repository = dataMap.get("repository");
        if (!(repository instanceof Map<?, ?> repositoryMap)) {
            return List.of();
        }
        Object pullRequest = repositoryMap.get("pullRequest");
        if (!(pullRequest instanceof Map<?, ?> pullRequestMap)) {
            return List.of();
        }
        Object closingIssuesReferences = pullRequestMap.get("closingIssuesReferences");
        if (!(closingIssuesReferences instanceof Map<?, ?> referencesMap)) {
            return List.of();
        }
        Object nodes = referencesMap.get("nodes");
        if (!(nodes instanceof List<?> nodeList)) {
            return List.of();
        }

        List<GithubLinkedIssue> issues = new ArrayList<>();
        for (Object node : nodeList) {
            if (!(node instanceof Map<?, ?> nodeMap)) {
                continue;
            }
            Integer number = intValue(nodeMap.get("number"));
            if (number == null) {
                continue;
            }
            RepositoryRef repositoryRef = repositoryRef(owner, repo, nodeMap.get("repository"));
            issues.add(new GithubLinkedIssue(
                    repositoryRef.owner(),
                    repositoryRef.name(),
                    number,
                    stringValue(nodeMap.get("title")),
                    stringValue(nodeMap.get("state")),
                    stringValue(nodeMap.get("url")),
                    "GRAPHQL_CLOSING_ISSUES"
            ));
        }
        return dedupeLinkedIssues(issues);
    }

    List<GithubLinkedIssue> fromBody(
            String owner,
            String repo,
            String body,
            IssueDetailFetcher issueDetailFetcher
    ) {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        Map<String, GithubLinkedIssue> issuesByKey = new LinkedHashMap<>();
        Matcher keywordMatcher = CLOSING_KEYWORD_LINE.matcher(body);
        while (keywordMatcher.find() && issuesByKey.size() < MAX_LINKED_ISSUES) {
            Matcher referenceMatcher = ISSUE_REFERENCE.matcher(keywordMatcher.group());
            while (referenceMatcher.find() && issuesByKey.size() < MAX_LINKED_ISSUES) {
                RepositoryIssueRef ref = repositoryIssueRef(owner, repo, referenceMatcher);
                if (ref == null) {
                    continue;
                }
                GithubLinkedIssue issue = fetchIssueDetailOrReference(
                        ref.owner(),
                        ref.repo(),
                        ref.number(),
                        issueDetailFetcher
                );
                issuesByKey.putIfAbsent(issueKey(issue), issue);
            }
        }
        return List.copyOf(issuesByKey.values());
    }

    String summarizeGraphqlErrors(List<?> errors) {
        String summary = errors.stream()
                .limit(3)
                .map(this::graphqlErrorMessage)
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + "; " + right)
                .orElse("unknown GraphQL error");
        return SensitiveDataSanitizer.redactAndTruncate(
                summary,
                GithubRequestExecutor.MAX_RESPONSE_BODY_SUMMARY_LENGTH
        );
    }

    private GithubLinkedIssue fetchIssueDetailOrReference(
            String owner,
            String repo,
            Integer issueNumber,
            IssueDetailFetcher issueDetailFetcher
    ) {
        try {
            Map<String, Object> issue = issueDetailFetcher.fetch(owner, repo, issueNumber);
            return new GithubLinkedIssue(
                    owner,
                    repo,
                    issueNumber,
                    stringValue(issue.get("title")),
                    stringValue(issue.get("state")),
                    stringValue(issue.get("html_url")),
                    "PR_BODY_CLOSING_KEYWORD"
            );
        } catch (BusinessException exception) {
            log.warn("GitHub linked issue detail lookup failed, fallback to reference only, owner={}, repo={}, issueNumber={}, message={}",
                    owner, repo, issueNumber, SensitiveDataSanitizer.redact(exception.getMessage()));
            return new GithubLinkedIssue(
                    owner,
                    repo,
                    issueNumber,
                    null,
                    null,
                    "https://github.com/" + owner + "/" + repo + "/issues/" + issueNumber,
                    "PR_BODY_CLOSING_KEYWORD"
            );
        }
    }

    private List<GithubLinkedIssue> dedupeLinkedIssues(List<GithubLinkedIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        Map<String, GithubLinkedIssue> issuesByKey = new LinkedHashMap<>();
        for (GithubLinkedIssue issue : issues) {
            if (issue != null && issue.getNumber() != null) {
                issuesByKey.putIfAbsent(issueKey(issue), issue);
            }
        }
        return List.copyOf(issuesByKey.values());
    }

    private String issueKey(GithubLinkedIssue issue) {
        return normalizeKey(issue.getRepositoryOwner())
                + "/"
                + normalizeKey(issue.getRepositoryName())
                + "#"
                + issue.getNumber();
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private RepositoryIssueRef repositoryIssueRef(String defaultOwner, String defaultRepo, Matcher matcher) {
        if (matcher.group(3) != null) {
            return new RepositoryIssueRef(matcher.group(1), matcher.group(2), Integer.parseInt(matcher.group(3)));
        }
        if (matcher.group(6) != null) {
            return new RepositoryIssueRef(matcher.group(4), matcher.group(5), Integer.parseInt(matcher.group(6)));
        }
        if (matcher.group(7) != null) {
            return new RepositoryIssueRef(defaultOwner, defaultRepo, Integer.parseInt(matcher.group(7)));
        }
        return null;
    }

    private RepositoryRef repositoryRef(String defaultOwner, String defaultRepo, Object repository) {
        if (!(repository instanceof Map<?, ?> repositoryMap)) {
            return new RepositoryRef(defaultOwner, defaultRepo);
        }
        String name = stringValue(repositoryMap.get("name"));
        Object owner = repositoryMap.get("owner");
        String ownerLogin = defaultOwner;
        if (owner instanceof Map<?, ?> ownerMap && ownerMap.get("login") != null) {
            ownerLogin = ownerMap.get("login").toString();
        }
        return new RepositoryRef(
                StringUtils.hasText(ownerLogin) ? ownerLogin : defaultOwner,
                StringUtils.hasText(name) ? name : defaultRepo
        );
    }

    private String graphqlErrorMessage(Object error) {
        if (error instanceof Map<?, ?> errorMap && errorMap.get("message") != null) {
            return errorMap.get("message").toString();
        }
        return error == null ? "" : error.toString();
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    @FunctionalInterface
    interface IssueDetailFetcher {

        Map<String, Object> fetch(String owner, String repo, Integer issueNumber);
    }

    private record RepositoryRef(String owner, String name) {
    }

    private record RepositoryIssueRef(String owner, String repo, Integer number) {
    }
}
