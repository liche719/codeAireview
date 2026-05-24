package com.codepilot.module.command.fix;

public class NonRetryableFixTaskException extends IllegalStateException {

    public NonRetryableFixTaskException(String message) {
        super(message);
    }
}
