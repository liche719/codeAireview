package com.codepilot.module.git.parser;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.git.dto.GithubPrInfo;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GithubPrUrlParser {

    private static final Pattern PR_URL_PATTERN = Pattern.compile(
            "^https?://github\\.com/([^/]+)/([^/]+)/pull/(\\d+)(?:[/?#].*)?$",
            Pattern.CASE_INSENSITIVE
    );

    public GithubPrInfo parse(String prUrl) {
        if (prUrl == null || prUrl.isBlank()) {
            throw new BusinessException("prUrl must not be blank");
        }

        Matcher matcher = PR_URL_PATTERN.matcher(prUrl.trim());
        if (!matcher.matches()) {
            throw new BusinessException("invalid GitHub PR URL, expected https://github.com/{owner}/{repo}/pull/{number}");
        }

        return new GithubPrInfo(
                matcher.group(1),
                matcher.group(2),
                Integer.valueOf(matcher.group(3))
        );
    }
}

