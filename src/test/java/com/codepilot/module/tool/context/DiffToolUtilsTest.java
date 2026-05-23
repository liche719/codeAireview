package com.codepilot.module.tool.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiffToolUtilsTest {

    @Test
    void shouldMapAddedLinesToNewFileLineNumbers() {
        var addedLines = DiffToolUtils.addedLineEntries("""
                @@ -8,3 +8,4 @@
                 class Demo {
                +    String token = "abc123";
                     void run() {}
                +    String sql = "select * from user";
                 }
                """);

        assertThat(addedLines)
                .extracting(DiffToolUtils.AddedLine::newLineNumber)
                .containsExactly(9, 11);
        assertThat(addedLines)
                .extracting(DiffToolUtils.AddedLine::text)
                .containsExactly(
                        "String token = \"abc123\";",
                        "String sql = \"select * from user\";"
                );
    }
}
