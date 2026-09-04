package at.aimon.memory.core;

/** Base for every failure aimon-memory raises deliberately. Carries a stable code for API mapping. */
public class MemoryException extends RuntimeException {

    private final String code;

    public MemoryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public MemoryException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
