package at.aimon.memory.api.security;

import at.aimon.memory.core.MemoryException;

public class UnauthorizedException extends MemoryException {
    public UnauthorizedException(String message) {
        super("unauthorized", message);
    }
}
