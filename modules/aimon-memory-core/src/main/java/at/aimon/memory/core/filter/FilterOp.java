package at.aimon.memory.core.filter;

import java.util.Locale;

/** Comparison vocabulary. Anything outside this set is rejected at parse time. */
public enum FilterOp {
    EQ,
    /** Null-safe: renders as {@code IS DISTINCT FROM}, so a NULL column does not swallow the row. */
    NE, GT, GTE, LT, LTE, IN, NIN, CONTAINS, ICONTAINS, STARTS_WITH,
    /** Value is a boolean: whether the column is non-null. */
    EXISTS;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static FilterOp fromWire(String wire) {
        String key = wire.toLowerCase(Locale.ROOT);
        for (FilterOp op : values()) {
            if (op.wire().equals(key)) {
                return op;
            }
        }
        throw new FilterException("unknown filter operator: " + wire);
    }

    public boolean expectsList() {
        return this == IN || this == NIN;
    }
}
