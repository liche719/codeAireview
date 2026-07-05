package com.codepilot.module.command.git;

import com.codepilot.common.util.SensitiveDataSanitizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class ValidationOutputSanitizer {

    private static final int MAX_VALIDATION_OUTPUT_CHARS = 12000;

    String sanitizedOutput(Path outputFile) throws IOException {
        return truncateValidationOutput(SensitiveDataSanitizer.redact(readValidationOutput(outputFile)));
    }

    private String readValidationOutput(Path outputFile) throws IOException {
        if (outputFile == null || !Files.exists(outputFile)) {
            return "";
        }
        return Files.readString(outputFile, StandardCharsets.UTF_8);
    }

    private String truncateValidationOutput(String output) {
        if (output.length() <= MAX_VALIDATION_OUTPUT_CHARS) {
            return output;
        }
        return SensitiveDataSanitizer.truncatePreservingRedactionMarker(output, MAX_VALIDATION_OUTPUT_CHARS)
                + "\n... output truncated ...";
    }
}
