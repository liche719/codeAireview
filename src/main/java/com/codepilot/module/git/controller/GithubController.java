package com.codepilot.module.git.controller;

import com.codepilot.common.response.Result;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.git.dto.GithubPrInfo;
import com.codepilot.module.git.dto.ParsePrRequest;
import com.codepilot.module.git.parser.GithubPrUrlParser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/github")
public class GithubController {

    private final GithubPrUrlParser githubPrUrlParser;

    private final GithubClient githubClient;

    @PostMapping("/parse-pr")
    public Result<GithubPrInfo> parsePr(@Valid @RequestBody ParsePrRequest request) {
        return Result.success(githubPrUrlParser.parse(request.getPrUrl()));
    }

    @GetMapping("/pulls/files")
    public Result<List<GithubChangedFile>> listPullRequestFiles(
            @RequestParam String owner,
            @RequestParam String repo,
            @RequestParam Integer pullNumber
    ) {
        return Result.success(githubClient.listPullRequestFiles(owner, repo, pullNumber));
    }
}
