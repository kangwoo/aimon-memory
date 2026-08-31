package dev.dyad.api.security;

import dev.dyad.core.DyadException;

public class UnauthorizedException extends DyadException {
    public UnauthorizedException(String message) {
        super("unauthorized", message);
    }
}
