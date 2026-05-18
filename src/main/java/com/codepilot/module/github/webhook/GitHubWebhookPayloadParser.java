package com.codepilot.module.github.webhook;

import com.codepilot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

@Component
public class GitHubWebhookPayloadParser {

    private static final String PULL_REQUEST_EVENT = "pull_request";

    private static final Set<String> SUPPORTED_ACTIONS = Set.of("opened", "synchronize", "reopened");

    private final ObjectMapper objectMapper;

    public GitHubWebhookPayloadParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public GitHubPullRequestWebhookPayload parse(String event, String payload) {
        if (!PULL_REQUEST_EVENT.equals(event)) {
            return GitHubPullRequestWebhookPayload.ignored("unsupported event");
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            String action = text(root, "action");
            if (!SUPPORTED_ACTIONS.contains(action)) {
                return GitHubPullRequestWebhookPayload.ignored("unsupported action", action);
            }

            String owner = text(root.at("/repository/owner"), "login");
            String repo = text(root, "repository", "name");
            Integer pullNumber = integer(root, "pull_request", "number");
            String prUrl = text(root, "pull_request", "html_url");
            String title = text(root, "pull_request", "title");
            String headSha = text(root.at("/pull_request/head"), "sha");

            if (!StringUtils.hasText(owner) || !StringUtils.hasText(repo) || pullNumber == null) {
                throw new BusinessException("invalid GitHub pull_request webhook payload");
            }
            if (!StringUtils.hasText(prUrl)) {
                prUrl = "https://github.com/" + owner + "/" + repo + "/pull/" + pullNumber;
            }

            return GitHubPullRequestWebhookPayload.supported(action, owner, repo, pullNumber, prUrl, title, headSha);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("failed to parse GitHub webhook payload: " + exception.getMessage());
        }
    }

    private String text(JsonNode root, String fieldName) {
        JsonNode node = root == null ? null : root.get(fieldName);
        return node == null || node.isNull() ? null : node.asText();
    }

    private String text(JsonNode root, String parentName, String fieldName) {
        JsonNode parent = root == null ? null : root.get(parentName);
        return text(parent, fieldName);
    }

    private Integer integer(JsonNode root, String parentName, String fieldName) {
        JsonNode parent = root == null ? null : root.get(parentName);
        JsonNode node = parent == null ? null : parent.get(fieldName);
        return node == null || node.isNull() ? null : node.asInt();
    }
}
