package dev.dyad.llm;

import dev.dyad.core.DyadException;

public class LlmException extends DyadException {

    public LlmException(String message) {
        super("llm_failed", message);
    }

    public LlmException(String code, String message) {
        super(code, message);
    }

    public LlmException(String message, Throwable cause) {
        super("llm_failed", message, cause);
    }
}
