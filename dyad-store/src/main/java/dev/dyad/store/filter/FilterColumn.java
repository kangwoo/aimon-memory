package dev.dyad.store.filter;

/**
 * One filterable column: its SQL name and the type its operands are coerced to.
 *
 * @param jsonPath when set, the column is a jsonb document and the field addresses a key inside it
 */
public record FilterColumn(String sql, ValueType type, String jsonPath) {

    public enum ValueType {
        TEXT,
        INTEGER,
        DECIMAL,
        BOOLEAN,
        TIMESTAMP
    }

    public static FilterColumn text(String sql) {
        return new FilterColumn(sql, ValueType.TEXT, null);
    }

    public static FilterColumn integer(String sql) {
        return new FilterColumn(sql, ValueType.INTEGER, null);
    }

    public static FilterColumn decimal(String sql) {
        return new FilterColumn(sql, ValueType.DECIMAL, null);
    }

    public static FilterColumn timestamp(String sql) {
        return new FilterColumn(sql, ValueType.TIMESTAMP, null);
    }

    public boolean isText() {
        return type == ValueType.TEXT;
    }
}
