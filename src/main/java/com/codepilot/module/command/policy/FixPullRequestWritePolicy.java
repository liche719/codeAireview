package com.codepilot.module.command.policy;

import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.fix.NonRetryableFixTaskException;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class FixPullRequestWritePolicy {

    public void assertWritableSameRepo(PrCommandTask task, GithubPullRequestDetail detail) {
        String expectedRepo = task.getRepoOwner() + "/" + task.getRepoName();
        if (!expectedRepo.equalsIgnoreCase(detail.getHeadRepoFullName())
                || !expectedRepo.equalsIgnoreCase(detail.getBaseRepoFullName())) {
            throw new NonRetryableFixTaskException("仅允许对当前仓库中的分支执行修复。");
        }
        if (!StringUtils.hasText(detail.getHeadRef()) || !StringUtils.hasText(detail.getHeadRepoCloneUrl())) {
            throw new NonRetryableFixTaskException("PR head 分支信息不完整。");
        }
    }
}
