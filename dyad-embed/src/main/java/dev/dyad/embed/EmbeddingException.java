package dev.dyad.embed;

import dev.dyad.core.DyadException;

public class EmbeddingException extends DyadException {
    public EmbeddingException(String message) {
        super("embedding_failed", message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super("embedding_failed", message, cause);
    }
}
