package com.codepilot.module.review.diff;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiffLineMapperTest {

    private final DiffLineMapper mapper = new DiffLineMapper();

    @Test
    void shouldMapAddedLineToRightSide() {
        DiffLineMapping mapping = mapper.map("src/Demo.java", patch(), 11);

        assertThat(mapping.commentable()).isTrue();
        assertThat(mapping.line()).isEqualTo(11);
        assertThat(mapping.side()).isEqualTo("RIGHT");
    }

    @Test
    void shouldNotCommentDeletedLine() {
        DiffLineMapping mapping = mapper.map("src/Demo.java", deletionOnlyPatch(), 11);

        assertThat(mapping.commentable()).isFalse();
    }

    @Test
    void shouldNotCommentContextLine() {
        DiffLineMapping mapping = mapper.map("src/Demo.java", patch(), 10);

        assertThat(mapping.commentable()).isFalse();
    }

    @Test
    void shouldNotCommentLineOutsidePatch() {
        DiffLineMapping mapping = mapper.map("src/Demo.java", patch(), 99);

        assertThat(mapping.commentable()).isFalse();
    }

    @Test
    void shouldMapAddedLineInMultipleHunks() {
        String patch = """
                @@ -1,3 +1,4 @@
                 class Demo {
                +    private String name;
                 }
                @@ -20,3 +21,4 @@
                 void run() {
                +    String sql = "select * from user";
                 }
                """;

        DiffLineMapping mapping = mapper.map("src/Demo.java", patch, 22);

        assertThat(mapping.commentable()).isTrue();
        assertThat(mapping.line()).isEqualTo(22);
        assertThat(mapping.side()).isEqualTo("RIGHT");
    }

    private String patch() {
        return """
                @@ -10,4 +10,5 @@
                 public void run() {
                -    String oldSql = sql;
                +    String sql = "select * from user where name = '" + name + "'";
                     System.out.println(sql);
                 }
                """;
    }

    private String deletionOnlyPatch() {
        return """
                @@ -10,4 +10,3 @@
                 public void run() {
                -    String oldSql = sql;
                     System.out.println(sql);
                 }
                """;
    }
}
