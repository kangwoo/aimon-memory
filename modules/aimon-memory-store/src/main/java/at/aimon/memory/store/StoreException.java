package at.aimon.memory.store;

import at.aimon.memory.core.MemoryException;

public class StoreException extends MemoryException {

    public StoreException(String message) {
        super("store_failed", message);
    }

    public StoreException(String message, Throwable cause) {
        super("store_failed", message, cause);
    }
}
