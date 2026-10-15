package com.codepilot.module.command.fix;

import com.codepilot.common.util.MarkdownSanitizer;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.review.report.CommentMarkers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class FixResultCommenter {

    private static final int COMMENT_ERROR_LIMIT = 500;

    public static final int DRY_RUN_PATCH_PREVIEW_LIMIT = 2500;

    private final GithubClient githubClient;

    public void noFixableIssues(PrCommandTask task) {
        comment(task, "我没找到支持的可修复问题。请先运行 `@x-pilotx review`，或者把需求收敛到一个具体的代码问题。");
    }

    public void missingSnippetContext(PrCommandTask task) {
        comment(task, "我找到了可修复的问题，但没有加载到足够的代码上下文来生成安全补丁。");
    }

    public void patchNotGenerated(PrCommandTask task) {
        comment(task, "我无法为这次请求生成安全补丁。");
    }

    public void patchValidationFailed(PrCommandTask task, String message) {
        comment(task, "我生成了一个补丁，但校验失败，所以没有推送提交。\n\n" + truncate(message, COMMENT_ERROR_LIMIT));
    }

    public void fixFailed(PrCommandTask task, String message) {
        comment(task, "我没能完成这次修复请求。\n\n" + truncate(message, COMMENT_ERROR_LIMIT));
    }

    public void dryRunCompleted(
            PrCommandTask task,
            FixPatchScopeValidationResult stats,
            String summary,
            String patch
    ) {
        comment(task, """
                **CodePilot AI** 预演完成。

                摘要：%s

                计划变更：
                - 文件数：%d
                - 变更行数：%d

                ```diff
                %s
                ```

                如确认发布此补丁，请单独评论：`@x-pilotx 批准发布修复 %d`
                """.formatted(
                MarkdownSanitizer.sanitizeInlineText(summary, 500, "补丁已生成。"),
                stats.filesChanged(),
                stats.changedLines(),
                MarkdownSanitizer.sanitizeCodeBlockText(patch, DRY_RUN_PATCH_PREVIEW_LIMIT, ""),
                task.getId()
        ));
    }

    public void fixPushed(PrCommandTask task) {
        comment(task, "**CodePilot AI** 已推送修复提交"
                + (StringUtils.hasText(task.getCommitSha()) ? "：`" + task.getCommitSha() + "`" : "。"));
    }

    public void fixPullRequestCreated(PrCommandTask task) {
        comment(task, "**CodePilot AI** 已创建独立修复 PR："
                + MarkdownSanitizer.sanitizeInlineText(task.getCreatedPrUrl(), 500, "链接不可用")
                + "\n\n如需合并，请单独评论：`@x-pilotx 批准合并修复 " + task.getId() + "`");
    }

    public void fixPullRequestMerged(PrCommandTask task) {
        comment(task, "**CodePilot AI** 修复 PR #" + task.getCreatedPrNumber()
                + " 已通过独立审批完成合并。"
                + (StringUtils.hasText(task.getCommitSha()) ? "\n\nMerge SHA：`" + task.getCommitSha() + "`" : ""));
    }

    private void comment(PrCommandTask task, String body) {
        try {
            githubClient.createPullRequestComment(
                    task.getRepoOwner(),
                    task.getRepoName(),
                    task.getPrNumber(),
                    CommentMarkers.wrap(CommentMarkers.FIX_RESULT, body));
        } catch (Exception exception) {
            log.warn("Failed to comment PR command result, commandTaskId={}, message={}",
                    task.getId(), SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }

    private String truncate(String content, int maxLength) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        String redacted = SensitiveDataSanitizer.redact(content);
        if (redacted.length() <= maxLength) {
            return redacted;
        }
        return SensitiveDataSanitizer.truncatePreservingRedactionMarker(redacted, maxLength) + "...";
    }
}
