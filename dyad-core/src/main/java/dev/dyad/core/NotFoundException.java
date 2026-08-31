package dev.dyad.core;

public class NotFoundException extends DyadException {
    public NotFoundException(String what, String name) {
        super("not_found", what + " '" + name + "' not found");
    }
}
