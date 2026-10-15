package com.codepilot.module.review.diff;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DiffLineMapper {

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");

    public DiffLineMapping map(String filePath, String patch, Integer issueLineNumber) {
        if (!StringUtils.hasText(filePath) || !StringUtils.hasText(patch) || issueLineNumber == null) {
            return DiffLineMapping.notCommentable();
        }

        try {
            return mapPatch(patch, issueLineNumber);
        } catch (Exception exception) {
            return DiffLineMapping.notCommentable();
        }
    }

    /**
     * 把一段行区间映射成一条多行区间评论。
     *
     * <p>比单行严格得多，因为 GitHub 对 {@code start_line}/{@code line} 有硬约束：两者必须
     * <b>同一侧</b>、<b>都在 diff 内</b>、且构成 diff 里<b>连续</b>的一段。任何一条不满足就返回
     * 不可评论——调用方应据此<b>降级为单行评论</b>，而不是丢弃这个 finding。</p>
     *
     * @param startLine 区间起始行
     * @param endLine   区间结束行（即评论锚定行）
     */
    public DiffLineMapping mapRange(String filePath, String patch, Integer startLine, Integer endLine) {
        if (!StringUtils.hasText(filePath) || !StringUtils.hasText(patch)
                || startLine == null || endLine == null || startLine >= endLine) {
            return DiffLineMapping.notCommentable();
        }

        try {
            return mapRangeInPatch(patch, startLine, endLine);
        } catch (Exception exception) {
            return DiffLineMapping.notCommentable();
        }
    }

    private DiffLineMapping mapRangeInPatch(String patch, int startLine, int endLine) {
        int oldLine = 0;
        int newLine = 0;
        boolean inHunk = false;
        Set<Integer> addedLines = new HashSet<>();
        Set<Integer> deletedLines = new HashSet<>();

        for (String line : patch.split("\\R")) {
            Matcher matcher = HUNK_HEADER.matcher(line);
            if (matcher.matches()) {
                // 进入新 hunk 前先判断上一段。区间必须整体落在同一个 hunk 内。
                DiffLineMapping resolved = resolveRangeWithinHunk(addedLines, deletedLines, startLine, endLine);
                if (resolved != null) {
                    return resolved;
                }
                addedLines.clear();
                deletedLines.clear();
                oldLine = Integer.parseInt(matcher.group(1));
                newLine = Integer.parseInt(matcher.group(2));
                inHunk = true;
                continue;
            }
            if (!inHunk || line.startsWith("\\ No newline")) {
                continue;
            }

            if (line.startsWith("+") && !line.startsWith("+++")) {
                addedLines.add(newLine);
                newLine++;
                continue;
            }
            if (line.startsWith("-") && !line.startsWith("---")) {
                deletedLines.add(oldLine);
                oldLine++;
                continue;
            }
            oldLine++;
            newLine++;
        }

        DiffLineMapping resolved = resolveRangeWithinHunk(addedLines, deletedLines, startLine, endLine);
        return resolved == null ? DiffLineMapping.notCommentable() : resolved;
    }

    /**
     * @return 命中则返回区间映射；该 hunk 不包含完整区间时返回 {@code null}
     */
    private DiffLineMapping resolveRangeWithinHunk(
            Set<Integer> addedLines,
            Set<Integer> deletedLines,
            int startLine,
            int endLine
    ) {
        if (isContiguousRange(addedLines, startLine, endLine)) {
            return DiffLineMapping.range("RIGHT", startLine, endLine);
        }
        if (isContiguousRange(deletedLines, startLine, endLine)) {
            return DiffLineMapping.range("LEFT", startLine, endLine);
        }
        return null;
    }

    /**
     * 区间内每一个行号都必须属于同一侧，缺一个就说明区间跨越了上下文行或 hunk 边界。
     */
    private boolean isContiguousRange(Set<Integer> sideLines, int startLine, int endLine) {
        if (sideLines.isEmpty()) {
            return false;
        }
        for (int line = startLine; line <= endLine; line++) {
            if (!sideLines.contains(line)) {
                return false;
            }
        }
        return true;
    }

    private DiffLineMapping mapPatch(String patch, Integer issueLineNumber) {
        int oldLine = 0;
        int newLine = 0;
        boolean inHunk = false;
        DiffLineMapping deletedLineCandidate = null;
        DiffLineMapping contextLineCandidate = null;

        for (String line : patch.split("\\R")) {
            Matcher matcher = HUNK_HEADER.matcher(line);
            if (matcher.matches()) {
                oldLine = Integer.parseInt(matcher.group(1));
                newLine = Integer.parseInt(matcher.group(2));
                inHunk = true;
                continue;
            }
            if (!inHunk || line.startsWith("\\ No newline")) {
                continue;
            }

            if (line.startsWith("+") && !line.startsWith("+++")) {
                if (newLine == issueLineNumber) {
                    return DiffLineMapping.right(newLine);
                }
                newLine++;
                continue;
            }

            if (line.startsWith("-") && !line.startsWith("---")) {
                if (oldLine == issueLineNumber && deletedLineCandidate == null) {
                    deletedLineCandidate = DiffLineMapping.left(oldLine);
                }
                oldLine++;
                continue;
            }

            if (oldLine == issueLineNumber || newLine == issueLineNumber) {
                if (contextLineCandidate == null) {
                    contextLineCandidate = DiffLineMapping.right(newLine);
                }
            }
            oldLine++;
            newLine++;
        }

        if (deletedLineCandidate != null) {
            return deletedLineCandidate;
        }
        return contextLineCandidate == null ? DiffLineMapping.notCommentable() : contextLineCandidate;
    }
}
