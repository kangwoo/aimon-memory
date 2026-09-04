package at.aimon.memory.embed;

import at.aimon.memory.core.MemoryException;

public class EmbeddingException extends MemoryException {
    public EmbeddingException(String message) {
        super("embedding_failed", message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super("embedding_failed", message, cause);
    }
}
