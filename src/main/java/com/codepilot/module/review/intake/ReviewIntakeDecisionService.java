package com.codepilot.module.review.intake;

import com.codepilot.module.review.entity.ReviewFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 入站决策：这次 PR 值不值得跑一遍完整审查（2026-11-02）。
 *
 * <p><b>为什么加这一层</b>：参考 DeepSeek 的 dsh-superagent，它在"收到触发"和"开任务"之间
 * 有一层 Eval——先判断值不值得做，不值得就记一条带理由的决策，而不是先做了再丢结果。
 *
 * <p>我们现在的链路是"先审出来 → 再抑制"（生产上 PUBLISH 100 / SUPPRESS 112），
 * 等于一半的模型调用于是白做的。这一层要省的是**整次审查**，不只是评论。
 *
 * <p><b>边界</b>：只在"明显不值得"时才跳过（例如只改了文档/锁文件），
 * 宁可多审一次，也不要把该审的跳过——误挡一次正常合并，比多发一条评论严重得多。
 */
@Slf4j
@Service
public class ReviewIntakeDecisionService {

    /** 纯文档/依赖锁文件，改了基本不需要业务审查 */
    private static final List<String> TRIVIAL_SUFFIXES = List.of(
            ".md", ".mdx", ".txt", ".rst", ".adoc",
            ".lock", ".lockb", "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "go.sum");

    @Value("${codepilot.intake.skip-trivial-changes:false}")
    private boolean skipTrivialChanges = false;

    /** 决策结果：值得审 / 不值得审（附理由）。 */
    public record Decision(boolean allowed, String code, String reason) {
        public static Decision allow() {
            return new Decision(true, "PROCEED", "");
        }

        public static Decision skip(String code, String reason) {
            return new Decision(false, code, reason);
        }
    }

    /**
     * 对本次 PR 的变更文件做一次轻量判断。
     *
     * <p>默认**不跳过**（开关关着）：先把决策记进日志，攒够数据再决定阈值。
     */
    public Decision decide(Long taskId, List<ReviewFile> files) {
        if (files == null || files.isEmpty()) {
            return decideAndLog(taskId, Decision.skip("EMPTY", "没有变更文件"));
        }
        if (!skipTrivialChanges) {
            return decideAndLog(taskId, Decision.allow());
        }
        boolean allTrivial = files.stream().allMatch(file -> isTrivial(file.getFilePath()));
        if (allTrivial) {
            return decideAndLog(taskId, Decision.skip("TRIVIAL",
                    "只改了文档或依赖锁文件（" + files.size() + " 个），跳过审查"));
        }
        return decideAndLog(taskId, Decision.allow());
    }

    private boolean isTrivial(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return TRIVIAL_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    private Decision decideAndLog(Long taskId, Decision decision) {
        // 统一前缀：以后可以从日志直接统计"审了多少、跳了多少、为什么跳"
        log.info("review_intake_decision taskId={} proceed={} code={} reason={}",
                taskId, decision.allowed(), decision.code(), decision.reason());
        return decision;
    }
}
