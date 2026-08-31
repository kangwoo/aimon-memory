package dev.dyad.core;

/** Base for every failure Dyad raises deliberately. Carries a stable code for API mapping. */
public class DyadException extends RuntimeException {

    private final String code;

    public DyadException(String code, String message) {
        super(message);
        this.code = code;
    }

    public DyadException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
