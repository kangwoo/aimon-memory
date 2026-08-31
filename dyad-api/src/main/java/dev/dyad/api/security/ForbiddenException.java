package dev.dyad.api.security;

import dev.dyad.core.DyadException;

public class ForbiddenException extends DyadException {
    public ForbiddenException(String message) {
        super("forbidden", message);
    }
}
