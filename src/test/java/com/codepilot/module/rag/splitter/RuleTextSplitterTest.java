package com.codepilot.module.rag.splitter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleTextSplitterTest {

    private final RuleTextSplitter splitter = new RuleTextSplitter();

    @Test
    void shouldSplitMultiParagraphTextIntoChunks() {
        String paragraph = """
                1. 禁止 SELECT *，必须明确查询字段。
                2. 涉及用户输入的 SQL 必须使用参数绑定，禁止字符串拼接。
                3. UPDATE 和 DELETE 必须带 WHERE 条件。
                4. 分页查询必须限制 pageSize 最大值。
                """;
        String content = paragraph.repeat(8);

        var chunks = splitter.split(content);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk).isNotBlank());
    }

    @Test
    void shouldReturnEmptyListWhenTextIsBlank() {
        assertThat(splitter.split("   ")).isEmpty();
    }
}
