package dev.dyad.core.config;

import dev.dyad.core.DyadException;

/**
 * A workspace configuration the system refuses to store.
 *
 * <p>Fail closed at the door, exactly as {@link dev.dyad.core.filter.FilterException} does for
 * predicates, and for the same reason: silently ignoring an override is worse than rejecting it. A
 * misspelled key or a weight vector that does not sum to 1.00 used to be accepted with a 200 and
 * then dropped on the next read, so a tuning session produced default rankings with nothing anywhere
 * to say why.
 */
public class ConfigurationException extends DyadException {

    public ConfigurationException(String message) {
        super("bad_configuration", message);
    }
}
