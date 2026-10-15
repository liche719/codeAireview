package com.codepilot.module.review.processor;

import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.diff.DiffLineMapping;
import com.codepilot.module.review.entity.ReviewIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 判断一段建议代码能否生成 GitHub 的<b>一键应用</b> {@code suggestion} 块。
 *
 * <h2>为什么要这么保守</h2>
 * {@code suggestion} 点一下就会改用户的代码。发一个应用不了的建议，用户点下去得到报错或错内容，
 * 比压根不给建议更伤信任。因此宁可取消一键应用、退化成 {@code diff} 块，
 * 也不发一个"看起来能点"的建议。
 *
 * <h2>校验条件</h2>
 * <ol>
 *   <li>建议代码非空</li>
 *   <li>落点必须可评论，且位于 diff <b>新侧</b>——删除侧的内容在应用后就不存在了</li>
 *   <li>行数不超过上限，避免把一个文件级改写塞进一行评论</li>
 * </ol>
 *
 * <p>「区间内不得含被删除的行」这条由 {@code DiffLineMapper.mapRange} 保证：
 * 它只在整个区间都是新侧<b>连续新增</b>行时才返回 RIGHT 侧区间。</p>
 */
@Component
@RequiredArgsConstructor
public class ReviewSuggestionValidator {

    private static final String SIDE_RIGHT = "RIGHT";

    private final ReviewProperties reviewProperties;

    public boolean isApplicable(ReviewIssue issue, DiffLineMapping mapping) {
        if (!reviewProperties.isSuggestionEnabled()) {
            return false;
        }
        if (issue == null || !StringUtils.hasText(issue.getSuggestedCode())) {
            return false;
        }
        if (mapping == null || !mapping.commentable()) {
            return false;
        }
        if (!SIDE_RIGHT.equalsIgnoreCase(mapping.side())) {
            return false;
        }
        return countLines(issue.getSuggestedCode()) <= Math.max(1, reviewProperties.getSuggestionMaxLines());
    }

    /**
     * 统计代码块的行数：忽略结尾换行，避免"最后多一个 \n"把行数算多一行而被误判超限。
     *
     * <p>先把 CRLF/CR 归一成 LF 再数：否则 {@code "a\r\nb\r\n"} 去掉结尾 {@code \n} 后会剩下
     * 一个落单的 {@code \r}，被 {@code \R} 当成又一个行尾，把 2 行算成 3 行。</p>
     */
    static int countLines(String code) {
        if (code == null || code.isEmpty()) {
            return 0;
        }
        String normalized = code.replace("\r\n", "\n").replace('\r', '\n');
        int end = normalized.length();
        while (end > 0 && normalized.charAt(end - 1) == '\n') {
            end--;
        }
        if (end == 0) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < end; i++) {
            if (normalized.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
