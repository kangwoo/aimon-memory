package dev.dyad.store.filter;

import dev.dyad.core.filter.Filter;
import dev.dyad.core.filter.FilterException;
import dev.dyad.core.filter.FilterOp;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link Filter} into SQL.
 *
 * <p>Two behaviours are worth stating outright.
 *
 * <p><b>{@code ne} is null-safe.</b> It renders as {@code IS DISTINCT FROM}, not {@code <>}. In
 * three-valued logic {@code level <> 'explicit'} drops every row where the column is NULL, so a user
 * asking for "not explicit" silently loses rows that are not explicit. {@code nin} carries the same
 * fix.
 *
 * <p><b>Coercion is strict.</b> {@code {"times_derived": {"gte": "many"}}} is a 422, not a zero.
 * Silently coercing a bad operand produces a query that runs and returns the wrong rows, which is
 * strictly worse than an error.
 */
public final class FilterCompiler {

    private final FilterSchema schema;

    public FilterCompiler(FilterSchema schema) {
        this.schema = schema;
    }

    public CompiledFilter compile(Filter filter) {
        if (filter == null || filter instanceof Filter.All) {
            return CompiledFilter.MATCH_ALL;
        }
        List<Object> params = new ArrayList<>();
        String sql = render(filter, params);
        return new CompiledFilter(sql, params);
    }

    private String render(Filter filter, List<Object> params) {
        return switch (filter) {
            case Filter.All ignored -> "TRUE";
            case Filter.And and -> join(and.operands(), " AND ", params);
            case Filter.Or or -> join(or.operands(), " OR ", params);
            case Filter.Not not -> "NOT (" + render(not.operand(), params) + ")";
            case Filter.Cmp cmp -> renderCmp(cmp, params);
        };
    }

    private String join(List<Filter> operands, String separator, List<Object> params) {
        List<String> parts = new ArrayList<>(operands.size());
        for (Filter operand : operands) {
            parts.add(render(operand, params));
        }
        return "(" + String.join(separator, parts) + ")";
    }

    private String renderCmp(Filter.Cmp cmp, List<Object> params) {
        FilterColumn column = schema.require(cmp.field());
        String col = column.sql();

        if (cmp.op() == FilterOp.EXISTS) {
            return Boolean.TRUE.equals(cmp.value()) ? col + " IS NOT NULL" : col + " IS NULL";
        }
        if (cmp.op().expectsList()) {
            List<?> values = (List<?>) cmp.value();
            List<String> placeholders = new ArrayList<>(values.size());
            for (Object value : values) {
                params.add(coerce(cmp.field(), column, value));
                placeholders.add("?");
            }
            String list = "(" + String.join(", ", placeholders) + ")";
            // NOT IN over a nullable column drops NULL rows in three-valued logic; the guard restores them.
            return cmp.op() == FilterOp.IN ? col + " IN " + list : "(" + col + " IS NULL OR " + col + " NOT IN " + list + ")";
        }
        if (cmp.value() == null) {
            return switch (cmp.op()) {
                case EQ -> col + " IS NULL";
                case NE -> col + " IS NOT NULL";
                default -> throw new FilterException(
                        "'" + cmp.op().wire() + "' on field '" + cmp.field() + "' does not accept null");
            };
        }

        Object value = coerce(cmp.field(), column, cmp.value());
        return switch (cmp.op()) {
            case EQ -> {
                params.add(value);
                yield col + " = ?";
            }
            case NE -> {
                params.add(value);
                yield col + " IS DISTINCT FROM ?";
            }
            case GT -> {
                params.add(value);
                yield col + " > ?";
            }
            case GTE -> {
                params.add(value);
                yield col + " >= ?";
            }
            case LT -> {
                params.add(value);
                yield col + " < ?";
            }
            case LTE -> {
                params.add(value);
                yield col + " <= ?";
            }
            case CONTAINS -> {
                requireText(cmp, column);
                params.add(value);
                yield "position(? in " + col + ") > 0";
            }
            case ICONTAINS -> {
                requireText(cmp, column);
                params.add(value);
                yield "position(lower(?) in lower(" + col + ")) > 0";
            }
            case STARTS_WITH -> {
                requireText(cmp, column);
                params.add(value);
                yield "starts_with(" + col + ", ?)";
            }
            case IN, NIN, EXISTS -> throw new IllegalStateException("handled above");
        };
    }

    private static void requireText(Filter.Cmp cmp, FilterColumn column) {
        if (!column.isText()) {
            throw new FilterException(
                    "'" + cmp.op().wire() + "' only applies to text fields; '" + cmp.field() + "' is " + column.type());
        }
    }

    private static Object coerce(String field, FilterColumn column, Object value) {
        try {
            return switch (column.type()) {
                case TEXT -> {
                    if (value instanceof Boolean || value instanceof Number) {
                        yield String.valueOf(value);
                    }
                    if (!(value instanceof String s)) {
                        throw new FilterException("field '" + field + "' expects a string");
                    }
                    yield s;
                }
                case INTEGER -> {
                    if (value instanceof Number n) {
                        yield n.longValue();
                    }
                    yield Long.parseLong(value.toString().trim());
                }
                case DECIMAL -> {
                    if (value instanceof Number n) {
                        yield n.doubleValue();
                    }
                    yield new BigDecimal(value.toString().trim()).doubleValue();
                }
                case BOOLEAN -> {
                    if (value instanceof Boolean b) {
                        yield b;
                    }
                    String s = value.toString().trim();
                    if (!"true".equalsIgnoreCase(s) && !"false".equalsIgnoreCase(s)) {
                        throw new FilterException("field '" + field + "' expects a boolean");
                    }
                    yield Boolean.parseBoolean(s);
                }
                case TIMESTAMP -> Timestamp.from(toInstant(field, value));
            };
        } catch (NumberFormatException e) {
            throw new FilterException(
                    "field '" + field + "' expects " + column.type().name().toLowerCase(java.util.Locale.ROOT)
                            + ", got '" + value + "'");
        }
    }

    private static Instant toInstant(String field, Object value) {
        if (value instanceof Number n) {
            return Instant.ofEpochSecond(n.longValue());
        }
        String raw = value.toString().trim();
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return Instant.parse(raw);
            } catch (DateTimeParseException e) {
                throw new FilterException(
                        "field '" + field + "' expects an ISO-8601 timestamp or epoch seconds, got '" + raw + "'");
            }
        }
    }
}
