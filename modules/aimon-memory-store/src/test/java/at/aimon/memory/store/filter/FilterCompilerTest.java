package at.aimon.memory.store.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.filter.Filter;
import at.aimon.memory.core.filter.FilterException;

class FilterCompilerTest {

    private final FilterCompiler compiler = new FilterCompiler(FilterSchema.CONCLUSIONS);

    private CompiledFilter compile(Map<String, Object> raw) {
        return compiler.compile(Filter.parse(raw));
    }

    /**
     * The whole reason {@code ne} is special-cased.
     *
     * <p>{@code level <> 'explicit'} is NULL when the column is NULL, and a NULL predicate drops the
     * row. So "not explicit" would silently exclude every row that is not explicit but has a NULL
     * there — the exact opposite of what was asked.
     */
    @Test
    void notEqualsIsNullSafe() {
        CompiledFilter compiled = compile(Map.of("session_name", Map.of("ne", "s1")));
        assertThat(compiled.sql()).isEqualTo("c.session_name IS DISTINCT FROM ?");
        assertThat(compiled.sql()).doesNotContain("<>");
        assertThat(compiled.params()).containsExactly("s1");
    }

    @Test
    void notInKeepsNullRows() {
        CompiledFilter compiled = compile(Map.of("level", Map.of("nin", List.of("explicit", "inductive"))));
        assertThat(compiled.sql()).isEqualTo("(c.level IS NULL OR c.level NOT IN (?, ?))");
        assertThat(compiled.params()).containsExactly("explicit", "inductive");
    }

    @Test
    void nullOperandsBecomeNullPredicates() {
        java.util.Map<String, Object> isNull = new java.util.HashMap<>();
        isNull.put("expires_at", null);
        assertThat(compile(isNull).sql()).isEqualTo("c.expires_at IS NULL");

        java.util.Map<String, Object> inner = new java.util.HashMap<>();
        inner.put("ne", null);
        assertThat(compile(Map.of("expires_at", inner)).sql()).isEqualTo("c.expires_at IS NOT NULL");
    }

    @Test
    void existsMapsToNullChecks() {
        assertThat(compile(Map.of("confidence", Map.of("exists", true))).sql()).isEqualTo("c.confidence IS NOT NULL");
        assertThat(compile(Map.of("confidence", Map.of("exists", false))).sql()).isEqualTo("c.confidence IS NULL");
    }

    @Test
    void valuesAreAlwaysBoundNeverInlined() {
        CompiledFilter compiled = compile(Map.of("content", Map.of("icontains", "'; DROP TABLE conclusions --")));
        assertThat(compiled.sql()).doesNotContain("DROP");
        assertThat(compiled.params()).containsExactly("'; DROP TABLE conclusions --");
    }

    @Test
    void unknownFieldsAreRejectedNotIgnored() {
        assertThatThrownBy(() -> compile(Map.of("observer", "alice"))).isInstanceOf(FilterException.class)
                .hasMessageContaining("unknown filter field 'observer'");

        // The pair columns are deliberately absent from the whitelist: they are the scope, and a
        // filter that could set them would be a filter that could leave the pair.
        assertThat(FilterSchema.CONCLUSIONS.fields()).doesNotContain("observer", "observed", "workspace_name");
    }

    /** A coerced-away operand produces a query that runs and returns the wrong rows. Worse than 422. */
    @Test
    void typeMismatchesAreRejected() {
        assertThatThrownBy(() -> compile(Map.of("times_derived", Map.of("gte", "many"))))
                .isInstanceOf(FilterException.class).hasMessageContaining("times_derived");

        assertThatThrownBy(() -> compile(Map.of("created_at", Map.of("gte", "not-a-date"))))
                .isInstanceOf(FilterException.class).hasMessageContaining("ISO-8601");

        assertThatThrownBy(() -> compile(Map.of("times_derived", Map.of("contains", "5"))))
                .isInstanceOf(FilterException.class).hasMessageContaining("only applies to text fields");
    }

    @Test
    void numbersAndTimestampsCoerceFromTheirWireForms() {
        assertThat(compile(Map.of("times_derived", Map.of("gte", 3))).params()).containsExactly(3L);
        assertThat(compile(Map.of("confidence", Map.of("lt", 0.5))).params()).containsExactly(0.5);
        assertThat(compile(Map.of("created_at", Map.of("gte", "2026-01-01T00:00:00Z"))).params()).hasSize(1)
                .allSatisfy(p -> assertThat(p).isInstanceOf(java.sql.Timestamp.class));
        assertThat(compile(Map.of("created_at", Map.of("gte", 1767225600))).params())
                .allSatisfy(p -> assertThat(p).isInstanceOf(java.sql.Timestamp.class));
    }

    @Test
    void nestedLogicKeepsParameterOrder() {
        CompiledFilter compiled = compile(Map.of("OR", List.of(Map.of("session_name", "s1"),
                Map.of("AND", List.of(Map.of("level", "inductive"), Map.of("times_derived", Map.of("gte", 2)))))));

        assertThat(compiled.sql()).isEqualTo("(c.session_name = ? OR (c.level = ? AND c.times_derived >= ?))");
        assertThat(compiled.params()).containsExactly("s1", "inductive", 2L);
    }

    @Test
    void emptyFilterMatchesEverything() {
        assertThat(compiler.compile(Filter.ALL)).isEqualTo(CompiledFilter.MATCH_ALL);
        assertThat(compiler.compile(null).isMatchAll()).isTrue();
    }
}
