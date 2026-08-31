package dev.dyad.store;

import dev.dyad.core.DyadException;

public class StoreException extends DyadException {

    public StoreException(String message) {
        super("store_failed", message);
    }

    public StoreException(String message, Throwable cause) {
        super("store_failed", message, cause);
    }
}
