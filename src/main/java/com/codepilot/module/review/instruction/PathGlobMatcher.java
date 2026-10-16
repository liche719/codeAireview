package com.codepilot.module.review.instruction;

import com.codepilot.module.review.classifier.ReviewFileClassifier;

import java.util.regex.Pattern;

/**
 * 路径 glob 匹配（R12）。
 *
 * <h2>支持的语法</h2>
 * <ul>
 *   <li>{@code **}：跨目录匹配任意层级（含 0 层）</li>
 *   <li>{@code *}：匹配<b>单个</b>路径段内的任意字符，不跨 {@code /}</li>
 *   <li>{@code ?}：匹配单段内的一个字符</li>
 * </ul>
 *
 * <h2>一条刻意的宽容规则</h2>
 * 不含 {@code /} 的 glob（例如 {@code *.sql}）会被当作 {@code **}{@code /*.sql}——
 * 也就是"任意目录下的同名文件"。这是 {@code .gitignore} 一类工具的通行语义：
 * 写 {@code *.sql} 的人想要的是"所有 SQL 文件"，而不是"仓库根目录下的 SQL 文件"。
 * 不这样做的话，规则会静默地只对根目录生效，作者却以为覆盖了全仓库。
 *
 * <p>路径先经 {@link ReviewFileClassifier#normalizePath} 归一化（反斜杠转正斜杠、转小写、去首尾空白），
 * 因此 glob 里的大小写不敏感。</p>
 */
public final class PathGlobMatcher {

    private PathGlobMatcher() {
    }

    /**
     * @param filePath 待匹配的文件路径
     * @param glob     匹配模式；空白或 null 视为不匹配
     */
    public static boolean matches(String filePath, String glob) {
        String normalizedGlob = ReviewFileClassifier.normalizePath(glob);
        if (normalizedGlob.isEmpty()) {
            return false;
        }
        String normalizedPath = ReviewFileClassifier.normalizePath(filePath);
        if (normalizedPath.isEmpty()) {
            return false;
        }
        return Pattern.compile(toRegex(normalizedGlob)).matcher(normalizedPath).matches();
    }

    /**
     * 把 glob 编译成正则。
     *
     * <p>逐个字符扫描而不是链式 {@code replace}：链式替换会让 {@code **} 先被 {@code *} 的规则吃掉，
     * 于是 {@code **} 退化成单段匹配——这类 bug 在"正好只跨一层"的测试数据上不会暴露。</p>
     */
    static String toRegex(String normalizedGlob) {
        String glob = normalizedGlob.startsWith("/") ? normalizedGlob.substring(1) : normalizedGlob;
        if (!glob.contains("/")) {
            // 不含路径分隔符：按"任意目录下的同名文件"处理。
            glob = "**/" + glob;
        }

        StringBuilder regex = new StringBuilder("^");
        int index = 0;
        while (index < glob.length()) {
            char current = glob.charAt(index);
            if (current == '*') {
                boolean doubleStar = index + 1 < glob.length() && glob.charAt(index + 1) == '*';
                if (doubleStar) {
                    // 吃掉紧随其后的 '/'，让 `**/x` 也能匹配根目录下的 `x`。
                    if (index + 2 < glob.length() && glob.charAt(index + 2) == '/') {
                        regex.append("(?:.*/)?");
                        index += 3;
                    } else {
                        regex.append(".*");
                        index += 2;
                    }
                } else {
                    regex.append("[^/]*");
                    index++;
                }
                continue;
            }
            if (current == '?') {
                regex.append("[^/]");
                index++;
                continue;
            }
            if (current == '/') {
                regex.append('/');
                index++;
                continue;
            }
            regex.append(Pattern.quote(String.valueOf(current)));
            index++;
        }
        return regex.append('$').toString();
    }
}
