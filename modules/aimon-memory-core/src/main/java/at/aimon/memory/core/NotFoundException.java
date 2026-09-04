package at.aimon.memory.core;

public class NotFoundException extends MemoryException {
    public NotFoundException(String what, String name) {
        super("not_found", what + " '" + name + "' not found");
    }
}
