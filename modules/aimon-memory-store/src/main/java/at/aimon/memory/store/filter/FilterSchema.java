package at.aimon.memory.store.filter;

import java.util.LinkedHashMap;
import java.util.Map;

import at.aimon.memory.core.filter.FilterException;

/**
 * The whitelist of filterable fields.
 *
 * <p>An allowlist, never a denylist. Anything not named here is rejected with a 422 rather than
 * passed through, which is what keeps a filter from reaching a column that carries another pair's
 * scope. New columns are opt-in by definition.
 */
public final class FilterSchema {

    public static final FilterSchema CONCLUSIONS = conclusions();
    public static final FilterSchema MESSAGES = messages();

    private final Map<String, FilterColumn> columns;

    private FilterSchema(Map<String, FilterColumn> columns) {
        this.columns = Map.copyOf(columns);
    }

    private static FilterSchema conclusions() {
        Map<String, FilterColumn> columns = new LinkedHashMap<>();
        columns.put("id", FilterColumn.text("c.id"));
        columns.put("session_name", FilterColumn.text("c.session_name"));
        columns.put("level", FilterColumn.text("c.level"));
        columns.put("content", FilterColumn.text("c.content"));
        columns.put("content_norm", FilterColumn.text("c.content_norm"));
        columns.put("sync_state", FilterColumn.text("c.sync_state"));
        columns.put("confidence", FilterColumn.decimal("c.confidence"));
        columns.put("times_derived", FilterColumn.integer("c.times_derived"));
        columns.put("created_at", FilterColumn.timestamp("c.created_at"));
        columns.put("updated_at", FilterColumn.timestamp("c.updated_at"));
        columns.put("last_reinforced_at", FilterColumn.timestamp("c.last_reinforced_at"));
        columns.put("expires_at", FilterColumn.timestamp("c.expires_at"));
        return new FilterSchema(columns);
    }

    private static FilterSchema messages() {
        Map<String, FilterColumn> columns = new LinkedHashMap<>();
        columns.put("id", FilterColumn.integer("m.id"));
        columns.put("session_name", FilterColumn.text("m.session_name"));
        columns.put("peer_name", FilterColumn.text("m.peer_name"));
        columns.put("content", FilterColumn.text("m.content"));
        columns.put("token_count", FilterColumn.integer("m.token_count"));
        columns.put("seq_in_session", FilterColumn.integer("m.seq_in_session"));
        columns.put("created_at", FilterColumn.timestamp("m.created_at"));
        return new FilterSchema(columns);
    }

    public FilterColumn require(String field) {
        FilterColumn column = columns.get(field);
        if (column == null) {
            throw new FilterException(
                    "unknown filter field '" + field + "'; allowed: " + String.join(", ", columns.keySet()));
        }
        return column;
    }

    public java.util.Set<String> fields() {
        return columns.keySet();
    }
}
