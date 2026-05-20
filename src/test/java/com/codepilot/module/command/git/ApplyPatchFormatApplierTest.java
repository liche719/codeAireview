package com.codepilot.module.command.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApplyPatchFormatApplierTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldApplyUpdatePatchInApplyPatchFormat() throws Exception {
        Path file = tempDir.resolve("src/main/java/com/codepilot/demo/Sample.java");
        write(file, """
                package com.codepilot.demo;

                public class Sample {

                    public String unsafe(String name) {
                        String sql = "SELECT * FROM users WHERE name = '" + name + "'";
                        return sql;
                    }
                }
                """);

        String patch = """
                *** Begin Patch
                *** Update File: src/main/java/com/codepilot/demo/Sample.java
                @@
                 package com.codepilot.demo;

                 public class Sample {

                     public String unsafe(String name) {
                -        String sql = "SELECT * FROM users WHERE name = '" + name + "'";
                -        return sql;
                +        if (name == null || name.isBlank()) {
                +            throw new IllegalArgumentException("name is required");
                +        }
                +        return "SELECT id, name FROM users WHERE name = ?";
                     }
                 }
                *** End Patch
                """;

        ApplyPatchFormatApplier.ApplyPatchApplicationResult result = ApplyPatchFormatApplier.apply(tempDir, patch);

        assertThat(result.changedFiles()).isEqualTo(1);
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .contains("throw new IllegalArgumentException(\"name is required\");")
                .contains("return \"SELECT id, name FROM users WHERE name = ?\";")
                .doesNotContain("SELECT * FROM users WHERE name = '\" + name + \"'");
    }

    @Test
    void shouldCreateFileForAddPatch() throws Exception {
        String patch = """
                *** Begin Patch
                *** Add File: src/test/java/com/codepilot/demo/NewFile.java
                +package com.codepilot.demo;
                +
                +public class NewFile {
                +    public String hello() {
                +        return "hello";
                +    }
                +}
                *** End Patch
                """;

        ApplyPatchFormatApplier.ApplyPatchApplicationResult result = ApplyPatchFormatApplier.apply(tempDir, patch);

        Path file = tempDir.resolve("src/test/java/com/codepilot/demo/NewFile.java");
        assertThat(result.changedFiles()).isEqualTo(1);
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .contains("public class NewFile")
                .contains("return \"hello\";");
    }

    @Test
    void shouldDeleteFileForDeletePatch() throws Exception {
        Path file = tempDir.resolve("src/test/java/com/codepilot/demo/OldFile.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class OldFile {}\n", StandardCharsets.UTF_8);

        String patch = """
                *** Begin Patch
                *** Delete File: src/test/java/com/codepilot/demo/OldFile.java
                *** End Patch
                """;

        ApplyPatchFormatApplier.ApplyPatchApplicationResult result = ApplyPatchFormatApplier.apply(tempDir, patch);

        assertThat(result.changedFiles()).isEqualTo(1);
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void shouldDetectApplyPatchFormat() {
        assertThat(ApplyPatchFormatApplier.isApplyPatchFormat("""
                *** Begin Patch
                *** Update File: src/test/java/com/codepilot/demo/Sample.java
                @@
                +hello
                *** End Patch
                """)).isTrue();
    }

    private void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
