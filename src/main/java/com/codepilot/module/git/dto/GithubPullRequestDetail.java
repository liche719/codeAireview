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

    @JsonProperty("head")
    public void unpackHead(Map<String, Object> head) {
        if (head != null && head.get("sha") != null) {
            this.headSha = head.get("sha").toString();
        }
    }
}
