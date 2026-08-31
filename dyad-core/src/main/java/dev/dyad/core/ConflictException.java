package dev.dyad.core;

public class ConflictException extends DyadException {
    public ConflictException(String message) {
        super("conflict", message);
    }
}
