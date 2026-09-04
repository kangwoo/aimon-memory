package at.aimon.memory.llm;

import at.aimon.memory.core.MemoryException;

public class LlmException extends MemoryException {

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
