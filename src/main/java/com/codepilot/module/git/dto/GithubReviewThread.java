package com.codepilot.module.git.dto;

import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一条 GitHub PR review thread（行内评论线程）。
 *
 * <p>用于 R7 的误报抑制：维护者在 GitHub 上把一个线程 resolve 掉，
 * 就表示"这个问题不用管"，系统应据此不再重复提。</p>
 */
public record GithubReviewThread(
        String threadId,
        boolean resolved,
        String firstCommentAuthor,
        String firstCommentBody
) {

    /** 行内评论正文里嵌入的稳定 finding 指纹。 */
    private static final Pattern FINDING_FINGERPRINT = Pattern.compile("<!--\\s*codepilot-finding:([0-9a-f]{16})\\s*-->");

    public GithubReviewThread {
        firstCommentBody = firstCommentBody == null ? "" : firstCommentBody;
    }

    /**
     * 从首条评论正文里取出稳定 finding 指纹。
     *
     * <p>取不到就返回 null——调用方据此跳过该线程。<b>绝不能退化成用行号或标题去猜</b>，
     * 那会把不相干的线程关联到错误的 finding 上。</p>
     */
    public String findingFingerprint() {
        if (!StringUtils.hasText(firstCommentBody)) {
            return null;
        }
        Matcher matcher = FINDING_FINGERPRINT.matcher(firstCommentBody);
        return matcher.find() ? matcher.group(1) : null;
    }
}
