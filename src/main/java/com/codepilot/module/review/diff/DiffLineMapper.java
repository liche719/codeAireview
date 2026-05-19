package com.codepilot.module.review.diff;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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

    private DiffLineMapping mapPatch(String patch, Integer issueLineNumber) {
        int oldLine = 0;
        int newLine = 0;
        boolean inHunk = false;

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
                oldLine++;
                continue;
            }

            oldLine++;
            newLine++;
        }

        return DiffLineMapping.notCommentable();
    }
}
