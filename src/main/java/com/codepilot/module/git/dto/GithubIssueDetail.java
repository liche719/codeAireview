package com.codepilot.module.git.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

@Data
public class GithubIssueDetail {

    private Integer number;

    @JsonProperty("html_url")
    private String htmlUrl;

    private String title;

    private String body;

    private String state;

    private String repositoryOwner;

    private String repositoryName;

    @JsonProperty("repository")
    public void unpackRepository(Map<String, Object> repository) {
        if (repository == null) {
            return;
        }
        Object owner = repository.get("owner");
        if (owner instanceof Map<?, ?> ownerMap && ownerMap.get("login") != null) {
            this.repositoryOwner = ownerMap.get("login").toString();
        }
        if (repository.get("name") != null) {
            this.repositoryName = repository.get("name").toString();
        }
    }
}
