package com.codepilot.module.git.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class GithubChangedFile {

    private String filename;

    private String status;

    private Integer additions;

    private Integer deletions;

    private Integer changes;

    private String patch;

    @JsonProperty("raw_url")
    private String rawUrl;

    @JsonProperty("blob_url")
    private String blobUrl;
}

