package at.aimon.memory.api.security;

import at.aimon.memory.core.MemoryException;

public class ForbiddenException extends MemoryException {
    public ForbiddenException(String message) {
        super("forbidden", message);
    }
}
