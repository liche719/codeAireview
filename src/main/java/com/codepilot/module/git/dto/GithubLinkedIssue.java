package com.codepilot.module.git.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GithubLinkedIssue {

    private String repositoryOwner;

    private String repositoryName;

    private Integer number;

    private String title;

    private String state;

    private String htmlUrl;

    private String bodySummary;

    private String linkSource;
}
