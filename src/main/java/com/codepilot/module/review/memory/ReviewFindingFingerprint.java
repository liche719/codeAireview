package com.codepilot.module.review.memory;

import com.codepilot.module.review.entity.ReviewIssue;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * finding 指纹：用来判断"这次的问题是不是上次已经提过的那一个"。
 *
 * <h2>为什么不含行号</h2>
 * 行号会随 commit 漂移——同一处问题在 rebase 或前面插入几行之后行号就变了。
 * 若指纹含行号，去重会立刻失效。所以只用 <b>文件路径 + 问题类型 + 归一化标题</b>。
 *
 * <h2>为什么宁可不匹配，也不要过度匹配</h2>
 * 两个不同问题算出同一指纹，会让第二个被误判成"已提过"而<b>不再评论</b>——
 * 这是漏报，比重复评论严重得多。因此：
 * <ul>
 *   <li>归一化只做大小写与空白/标点的收敛，不做语义近似</li>
 *   <li><b>识别信息不足时返回 {@code null}</b>（例如标题与描述都为空），
 *       调用方据此视为"全新问题、永不抑制"，而不是拿一个粗糙指纹去撞</li>
 * </ul>
 *
 * <h2>双指纹 OR 匹配</h2>
 * 模型对同一条 finding 的措辞每次都可能不同。因此额外计算一个基于<b>建议代码</b>的
 * {@code codeFingerprint}，两者任一命中即视为同一问题——覆盖"同一问题换了措辞"与
 * "同一段代码换了描述"两种情况。
 */
public final class ReviewFindingFingerprint {

    /** 标题归一化后至少要留这么多字符，否则认为识别信息不足。 */
    private static final int MIN_TITLE_CHARS = 8;

    private static final int FINGERPRINT_HEX_LENGTH = 16;

    private ReviewFindingFingerprint() {
    }

    /**
     * @return 16 位十六进制指纹；识别信息不足时返回 {@code null}（调用方必须按"全新问题"处理）
     */
    public static String findingFingerprint(ReviewIssue issue) {
        if (issue == null) {
            return null;
        }
        String path = normalize(issue.getFilePath());
        String issueType = normalize(issue.getIssueType());
        if (!StringUtils.hasText(path) || !StringUtils.hasText(issueType)) {
            return null;
        }
        String identity = identityText(issue);
        if (!StringUtils.hasText(identity)) {
            return null;
        }
        return hash(path + "|" + issueType + "|" + identity);
    }

    /**
     * 基于建议内容的指纹，用于兜住"同一问题换了措辞"。
     *
     * @return 16 位十六进制指纹；没有可用建议内容时返回 {@code null}
     */
    public static String codeFingerprint(ReviewIssue issue) {
        if (issue == null) {
            return null;
        }
        String path = normalize(issue.getFilePath());
        String suggestion = normalize(issue.getSuggestion());
        if (!StringUtils.hasText(path) || !StringUtils.hasText(suggestion)) {
            return null;
        }
        return hash(path + "|code|" + suggestion);
    }

    /**
     * 有标题用标题；标题太短时退回描述的前一段。两者都不足以识别时返回 null。
     */
    private static String identityText(ReviewIssue issue) {
        String title = normalize(issue.getTitle());
        if (title.length() >= MIN_TITLE_CHARS) {
            return title;
        }
        String description = normalize(issue.getDescription());
        if (description.length() >= MIN_TITLE_CHARS) {
            return description.length() <= 160 ? description : description.substring(0, 160);
        }
        return null;
    }

    /**
     * 收敛大小写、空白与标点，保留中日韩文字（它们承载语义）。
     */
    static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String lowered = value.toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lowered.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < lowered.length(); i++) {
            char c = lowered.charAt(i);
            boolean keep = Character.isLetterOrDigit(c) || isCjk(c);
            if (keep) {
                normalized.append(c);
                lastWasSpace = false;
            } else if (!lastWasSpace) {
                normalized.append(' ');
                lastWasSpace = true;
            }
        }
        return normalized.toString().trim();
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3040 && c <= 0x30FF)
                || (c >= 0xAC00 && c <= 0xD7AF);
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hex = HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
            return hex.substring(0, FINGERPRINT_HEX_LENGTH);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is required but unavailable", exception);
        }
    }
}
