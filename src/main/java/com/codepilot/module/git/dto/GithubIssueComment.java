package com.codepilot.module.git.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

@Data
public class GithubIssueComment {

    private Long id;

    private String body;

    private String userLogin;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    @JsonProperty("user")
    public void unpackUser(Map<String, Object> user) {
        if (user != null && user.get("login") != null) {
            this.userLogin = user.get("login").toString();
        }
    }
}
