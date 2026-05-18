package com.codepilot.module.git.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GithubPrInfo {

    private String owner;

    private String repo;

    private Integer pullNumber;
}

