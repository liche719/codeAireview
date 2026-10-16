package com.codepilot.module.review.instruction;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 读取仓库内的路径级审查指令（R12）。
 *
 * <h2>安全约束：只从 <b>base</b> 分支读，绝不从 head</h2>
 * 这是整个能力的核心约束（借鉴 Greptile）。规则文件本身就在仓库里、会随 PR 变更，
 * 如果从 PR 的 head 读，那么<b>一个 PR 只要顺手改掉 {@code .codepilot/rules.md}
 * 就能放宽甚至取消对自己的审查</b>——攻击者不需要绕过任何权限，只需要提交一个文件。
 *
 * <p>从 base 分支读就切断了这条路：本 PR 对规则文件的修改要等它被合并、
 * 成为后续 PR 的 base 之后才会生效，而那时它已经被正常 review 过了。</p>
 *
 * <h2>失败一律降级为"没有指令"</h2>
 * 这是一条<b>旁路增强</b>：文件不存在、base 分支取不到、权限不足、内容过大——
 * 全都只记 warning 并返回空列表。绝不能因为"读不到一份可选的规则文件"就让审查失败。
 *
 * <p>因此调用方拿到的空列表有两种含义（"没配规则"与"读失败了"），这是刻意的：
 * 两者的处置完全相同，而区分它们只会把一条可选能力变成错误源。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PathInstructionProvider {

    private final GithubClient githubClient;

    private final ReviewProperties reviewProperties;

    /**
     * @return 规则列表；未启用、读不到或解析为空时返回空列表，<b>从不抛异常</b>
     */
    public List<PathInstruction> load(ReviewTask task) {
        if (reviewProperties == null || !reviewProperties.isPathInstructionsEnabled()) {
            return List.of();
        }
        if (githubClient == null || task == null) {
            return List.of();
        }
        String owner = task.getRepoOwner();
        String repo = task.getRepoName();
        Integer prNumber = task.getPrNumber();
        String path = reviewProperties.getPathInstructionsFile();
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repo) || prNumber == null
                || !StringUtils.hasText(path)) {
            return List.of();
        }

        try {
            String baseRef = resolveBaseRef(owner, repo, prNumber);
            if (!StringUtils.hasText(baseRef)) {
                log.warn("Path instructions skipped because the base ref is unavailable, taskId={}, owner={}, repo={}, pullNumber={}",
                        task.getId(), owner, repo, prNumber);
                return List.of();
            }
            String content = githubClient.getFileContent(owner, repo, path.trim(), baseRef);
            if (!StringUtils.hasText(content)) {
                return List.of();
            }
            List<PathInstruction> instructions =
                    PathInstructionParser.parse(content, reviewProperties.getPathInstructionsMaxChars());
            if (!instructions.isEmpty()) {
                log.info("Path instructions loaded from the base branch, taskId={}, baseRef={}, ruleCount={}",
                        task.getId(), baseRef, instructions.size());
            }
            return instructions;
        } catch (Exception exception) {
            // 旁路能力：读不到就当没有规则，审查继续。
            log.warn("Path instructions unavailable, continue without them, taskId={}, owner={}, repo={}, pullNumber={}, errorType={}, message={}",
                    task.getId(), owner, repo, prNumber,
                    exception.getClass().getSimpleName(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
            return List.of();
        }
    }

    /**
     * 取 base 分支名。
     *
     * <p>用 base <b>ref</b>（分支名）而不是 base sha：分支名在 PR 存续期间稳定，
     * 而 base sha 会随目标分支前进而变化——但两者都指向"PR 之外"的代码，
     * 关键是不能用 head 的任何东西。</p>
     */
    private String resolveBaseRef(String owner, String repo, Integer prNumber) {
        GithubPullRequestDetail detail = githubClient.getPullRequestDetail(owner, repo, prNumber);
        return detail == null ? null : detail.getBaseRef();
    }
}
