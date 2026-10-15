package com.codepilot.module.review.memory;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubReviewThread;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 误报抑制：把"维护者已经 resolve 掉的线程"记为 DISMISSED。
 *
 * <p>比监听 👍/👎 reaction 更贴近真实工作流——人在看代码时更自然的动作是 resolve 掉线程，
 * 而不是去点 emoji；而且 thread 的 resolved 状态是明确、可查询的。</p>
 *
 * <h2>两条硬约束</h2>
 * <ol>
 *   <li><b>只认"首条评论由本机器人发出"的线程。</b>任何人都能开一个线程并 resolve 它，
 *       如果照单全收，等于让人用 resolve 别人的讨论来关闭我们的 finding。</li>
 *   <li><b>任何失败都必须降级为"不抑制"，绝不能让审查失败。</b>这是旁路能力：
 *       GraphQL 权限不足、限流、网络抖动都只该记一条 warning。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewDismissalService {

    private static final String DISMISS_REASON = "thread resolved by a maintainer";

    private final GithubClient githubClient;

    private final ReviewFindingMemoryMapper reviewFindingMemoryMapper;

    private final ReviewProperties reviewProperties;

    /**
     * 同步一次被 resolve 的线程。
     *
     * @return 被标记为 DISMISSED 的 finding 数量；任何失败都返回 0 而不是抛异常
     */
    public int syncResolvedThreads(ReviewTask task) {
        if (!reviewProperties.isDismissalSyncEnabled() || task == null) {
            return 0;
        }
        String owner = task.getRepoOwner();
        String repo = task.getRepoName();
        Integer prNumber = task.getPrNumber();
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repo) || prNumber == null) {
            return 0;
        }

        List<GithubReviewThread> threads;
        String botLogin;
        try {
            threads = githubClient.listReviewThreads(owner, repo, prNumber);
            botLogin = githubClient.getAuthenticatedUserLogin();
        } catch (Exception exception) {
            // 旁路能力：拿不到线程就当没有任何抑制，审查继续。
            log.warn("Review dismissal sync skipped because GitHub review threads could not be read, "
                            + "taskId={}, errorType={}, message={}",
                    task.getId(), exception.getClass().getSimpleName(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
            return 0;
        }

        if (!StringUtils.hasText(botLogin)) {
            // 拿不到自己的登录名就无法判断"线程是不是我们开的"，宁可一条都不抑制。
            log.warn("Review dismissal sync skipped because the authenticated bot login is unavailable, taskId={}",
                    task.getId());
            return 0;
        }

        int dismissed = 0;
        for (GithubReviewThread thread : threads) {
            if (thread == null || !thread.resolved() || !isOwnThread(thread, botLogin)) {
                continue;
            }
            String fingerprint = thread.findingFingerprint();
            if (!StringUtils.hasText(fingerprint)) {
                continue;
            }
            try {
                dismissed += reviewFindingMemoryMapper.markDismissed(
                        owner, repo, prNumber, fingerprint, DISMISS_REASON);
            } catch (Exception exception) {
                log.warn("Failed to mark a finding as dismissed, taskId={}, fingerprint={}, errorType={}",
                        task.getId(), fingerprint, exception.getClass().getSimpleName());
            }
        }

        if (dismissed > 0) {
            log.info("Review dismissal sync applied, taskId={}, owner={}, repo={}, pullNumber={}, dismissed={}",
                    task.getId(), owner, repo, prNumber, dismissed);
        }
        return dismissed;
    }

    private boolean isOwnThread(GithubReviewThread thread, String botLogin) {
        return StringUtils.hasText(thread.firstCommentAuthor())
                && botLogin.equalsIgnoreCase(thread.firstCommentAuthor());
    }
}
