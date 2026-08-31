package dev.dyad.core.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A boolean predicate over conclusion metadata, expressed independently of any SQL dialect.
 *
 * <p>Wire form is a nested map:
 *
 * <pre>{@code
 * { "level": "explicit",
 *   "times_derived": { "gte": 2 },
 *   "OR": [ { "session_name": "s1" }, { "session_name": "s2" } ] }
 * }</pre>
 *
 * <p>A bare scalar means {@code eq}; a bare list means {@code in}. Everything else has to name its
 * operator. Field names are not validated here — the store owns the whitelist, because only the
 * store knows which columns exist and what type they are.
 */
public sealed interface Filter {

    /** Matches everything. The identity of {@code and}. */
    record All() implements Filter {}

    record And(List<Filter> operands) implements Filter {
        public And {
            operands = List.copyOf(operands);
        }
    }

    record Or(List<Filter> operands) implements Filter {
        public Or {
            operands = List.copyOf(operands);
        }
    }

    record Not(Filter operand) implements Filter {}

    record Cmp(String field, FilterOp op, Object value) implements Filter {}

    Filter ALL = new All();

    static Filter and(Filter... parts) {
        List<Filter> kept = new ArrayList<>();
        for (Filter f : parts) {
            if (f != null && !(f instanceof All)) {
                kept.add(f);
            }
        }
        return switch (kept.size()) {
            case 0 -> ALL;
            case 1 -> kept.get(0);
            default -> new And(kept);
        };
    }

    static Filter eq(String field, Object value) {
        return new Cmp(field, FilterOp.EQ, value);
    }

    /**
     * Parse the wire form.
     *
     * @throws FilterException on any shape the vocabulary does not cover
     */
    @SuppressWarnings("unchecked")
    static Filter parse(Map<String, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return ALL;
        }
        List<Filter> parts = new ArrayList<>();
        for (Map.Entry<String, ?> e : raw.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            switch (key.toUpperCase(java.util.Locale.ROOT)) {
                case "AND" -> parts.add(new And(parseList(value, "AND")));
                case "OR" -> parts.add(new Or(parseList(value, "OR")));
                case "NOT" -> {
                    if (!(value instanceof Map<?, ?> m)) {
                        throw new FilterException("NOT expects an object, got " + describe(value));
                    }
                    parts.add(new Not(parse((Map<String, ?>) m)));
                }
                default -> parts.add(parseField(key, value));
            }
        }
        return parts.size() == 1 ? parts.get(0) : new And(parts);
    }

    @SuppressWarnings("unchecked")
    private static List<Filter> parseList(Object value, String label) {
        if (!(value instanceof List<?> list)) {
            throw new FilterException(label + " expects an array, got " + describe(value));
        }
        if (list.isEmpty()) {
            throw new FilterException(label + " expects at least one operand");
        }
        List<Filter> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                throw new FilterException(label + " operands must be objects, got " + describe(item));
            }
            out.add(parse((Map<String, ?>) m));
        }
        return out;
    }

    private static Filter parseField(String field, Object value) {
        if (value instanceof Map<?, ?> ops) {
            if (ops.isEmpty()) {
                throw new FilterException("empty operator object for field '" + field + "'");
            }
            List<Filter> parts = new ArrayList<>();
            for (Map.Entry<?, ?> e : ops.entrySet()) {
                FilterOp op = FilterOp.fromWire(String.valueOf(e.getKey()));
                parts.add(new Cmp(field, op, checkOperand(field, op, e.getValue())));
            }
            return parts.size() == 1 ? parts.get(0) : new And(parts);
        }
        if (value instanceof List<?> list) {
            return new Cmp(field, FilterOp.IN, List.copyOf(list));
        }
        return new Cmp(field, FilterOp.EQ, value);
    }

    private static Object checkOperand(String field, FilterOp op, Object operand) {
        if (op.expectsList()) {
            if (!(operand instanceof List<?> list)) {
                throw new FilterException(
                        "'" + op.wire() + "' on field '" + field + "' expects an array, got " + describe(operand));
            }
            if (list.isEmpty()) {
                throw new FilterException("'" + op.wire() + "' on field '" + field + "' expects a non-empty array");
            }
            return List.copyOf(list);
        }
        if (op == FilterOp.EXISTS && !(operand instanceof Boolean)) {
            throw new FilterException("'exists' on field '" + field + "' expects a boolean");
        }
        if (operand instanceof List<?> || operand instanceof Map<?, ?>) {
            throw new FilterException(
                    "'" + op.wire() + "' on field '" + field + "' expects a scalar, got " + describe(operand));
        }
        return operand;
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        return value.getClass().getSimpleName();
    }
}
