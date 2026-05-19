package com.codepilot.module.git.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

@Data
public class GithubPullRequestDetail {

    private Integer number;

    @JsonProperty("html_url")
    private String htmlUrl;

    private String title;

    private String headSha;

    private String headRef;

    private String headRepoFullName;

    private String headRepoCloneUrl;

    private String baseRepoFullName;

    @JsonProperty("head")
    public void unpackHead(Map<String, Object> head) {
        if (head != null && head.get("sha") != null) {
            this.headSha = head.get("sha").toString();
        }
        if (head != null && head.get("ref") != null) {
            this.headRef = head.get("ref").toString();
        }
        Object repo = head == null ? null : head.get("repo");
        if (repo instanceof Map<?, ?> repoMap) {
            Object fullName = repoMap.get("full_name");
            Object cloneUrl = repoMap.get("clone_url");
            if (fullName != null) {
                this.headRepoFullName = fullName.toString();
            }
            if (cloneUrl != null) {
                this.headRepoCloneUrl = cloneUrl.toString();
            }
        }
    }

    @JsonProperty("base")
    public void unpackBase(Map<String, Object> base) {
        Object repo = base == null ? null : base.get("repo");
        if (repo instanceof Map<?, ?> repoMap) {
            Object fullName = repoMap.get("full_name");
            if (fullName != null) {
                this.baseRepoFullName = fullName.toString();
            }
        }
    }
}
