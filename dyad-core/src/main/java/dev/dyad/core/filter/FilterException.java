package dev.dyad.core.filter;

import dev.dyad.core.DyadException;

/**
 * A filter the system refuses to run.
 *
 * <p>Always fail closed: an unknown field or operator is an error, never a silently dropped
 * predicate. A dropped predicate on a pair-scoped query is a data leak, so there is no lenient mode.
 */
public class FilterException extends DyadException {
    public FilterException(String message) {
        super("bad_filter", message);
    }
}
