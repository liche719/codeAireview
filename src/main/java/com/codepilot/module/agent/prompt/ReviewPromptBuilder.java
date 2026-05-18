package com.codepilot.module.agent.prompt;

import org.springframework.stereotype.Component;

@Component
public class ReviewPromptBuilder {

    private static final String TEMPLATE = """
            你是一个资深 Java 后端代码审查专家。

            请根据下面的 GitHub Pull Request Diff，对代码进行审查。

            审查重点：
            1. 潜在 Bug；
            2. 空指针风险；
            3. SQL 注入风险；
            4. 权限校验问题；
            5. 性能问题；
            6. Java / Spring Boot 编码规范；
            7. 异常处理是否合理；
            8. 日志是否缺失；
            9. 是否需要补充单元测试。

            请严格按照 JSON 格式输出，不要输出 Markdown，不要输出额外解释。

            输出格式：
            {
              "issues": [
                {
                  "filePath": "文件路径",
                  "lineNumber": 行号，如果无法判断则为 null,
                  "issueType": "BUG_RISK | SECURITY | PERFORMANCE | STYLE | SQL_RISK | EXCEPTION_HANDLING | LOGGING | TEST_MISSING",
                  "severity": "HIGH | MEDIUM | LOW",
                  "title": "问题标题",
                  "description": "问题描述",
                  "suggestion": "修改建议",
                  "ruleReference": null
                }
              ],
              "summary": "本次文件审查总结"
            }

            文件路径：
            {{filePath}}

            代码 Diff：
            {{patch}}
            """;

    public String buildReviewPrompt(String filePath, String patch) {
        return TEMPLATE
                .replace("{{filePath}}", filePath == null ? "" : filePath)
                .replace("{{patch}}", patch == null ? "" : patch);
    }
}
