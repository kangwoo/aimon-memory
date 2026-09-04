package at.aimon.memory.core;

public class ConflictException extends MemoryException {
    public ConflictException(String message) {
        super("conflict", message);
    }
}
