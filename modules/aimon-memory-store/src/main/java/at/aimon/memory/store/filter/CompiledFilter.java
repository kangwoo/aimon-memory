package at.aimon.memory.store.filter;

import java.util.List;

/**
 * A filter turned into a SQL fragment and its bind values.
 *
 * <p>{@code sql} never contains a literal from the request — every operand is a placeholder. That is
 * not a stylistic preference: the fields come from user JSON.
 */
public record CompiledFilter(String sql, List<Object> params) {

    public static final CompiledFilter MATCH_ALL = new CompiledFilter("TRUE", List.of());

    public CompiledFilter {
        params = List.copyOf(params);
    }

    public boolean isMatchAll() {
        return "TRUE".equals(sql);
    }
}
