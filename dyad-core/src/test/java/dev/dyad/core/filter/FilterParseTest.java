package dev.dyad.core.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FilterParseTest {

    @Test
    void bareScalarMeansEquals() {
        assertThat(Filter.parse(Map.of("level", "explicit")))
                .isEqualTo(new Filter.Cmp("level", FilterOp.EQ, "explicit"));
    }

    @Test
    void bareListMeansIn() {
        Filter parsed = Filter.parse(Map.of("level", List.of("explicit", "deductive")));
        assertThat(parsed).isInstanceOf(Filter.Cmp.class);
        assertThat(((Filter.Cmp) parsed).op()).isEqualTo(FilterOp.IN);
    }

    @Test
    void nestedLogicalOperatorsParse() {
        Filter parsed =
                Filter.parse(
                        Map.of("OR", List.of(Map.of("session_name", "s1"), Map.of("session_name", "s2"))));
        assertThat(parsed).isInstanceOf(Filter.Or.class);
        assertThat(((Filter.Or) parsed).operands()).hasSize(2);
    }

    @Test
    void emptyFilterMatchesEverything() {
        assertThat(Filter.parse(Map.of())).isInstanceOf(Filter.All.class);
        assertThat(Filter.parse(null)).isInstanceOf(Filter.All.class);
    }

    /**
     * Every rejection below could plausibly have been "ignore it and carry on". None of them are: a
     * dropped predicate returns rows the caller asked not to see, and on a pair-scoped store that is
     * a disclosure, not a nuisance.
     */
    @Test
    void malformedFiltersFailClosed() {
        assertThatThrownBy(() -> Filter.parse(Map.of("level", Map.of("bogus", "x"))))
                .isInstanceOf(FilterException.class)
                .hasMessageContaining("unknown filter operator");

        assertThatThrownBy(() -> Filter.parse(Map.of("OR", "not-an-array")))
                .isInstanceOf(FilterException.class);

        assertThatThrownBy(() -> Filter.parse(Map.of("level", Map.of("in", "not-an-array"))))
                .isInstanceOf(FilterException.class);

        assertThatThrownBy(() -> Filter.parse(Map.of("level", Map.of("in", List.of()))))
                .isInstanceOf(FilterException.class);

        assertThatThrownBy(() -> Filter.parse(Map.of("times_derived", Map.of("gte", List.of(1, 2)))))
                .isInstanceOf(FilterException.class);

        assertThatThrownBy(() -> Filter.parse(Map.of("level", Map.of("exists", "yes"))))
                .isInstanceOf(FilterException.class);

        assertThatThrownBy(() -> Filter.parse(Map.of("level", Map.of())))
                .isInstanceOf(FilterException.class);
    }

    @Test
    void andCollapsesMatchAllOperands() {
        assertThat(Filter.and(Filter.ALL, Filter.ALL)).isInstanceOf(Filter.All.class);
        assertThat(Filter.and(Filter.ALL, Filter.eq("level", "explicit")))
                .isEqualTo(new Filter.Cmp("level", FilterOp.EQ, "explicit"));
    }

    /**
     * Parsing is recursive, so an unbounded nesting depth is an unbounded stack. A
     * {@link StackOverflowError} is not a {@link RuntimeException}: it escapes the API's error
     * handling entirely and the caller sees a dropped connection rather than a 422.
     */
    @Test
    void aFilterNestedPastTheLimitIsRejected() {
        Map<String, Object> deep = Map.of("level", "explicit");
        for (int i = 0; i < Filter.MAX_DEPTH + 2; i++) {
            deep = Map.of("NOT", deep);
        }
        Map<String, Object> tooDeep = deep;

        assertThatThrownBy(() -> Filter.parse(tooDeep))
                .isInstanceOf(FilterException.class)
                .hasMessageContaining("nested");
    }

    /** Depth is not the only size: a flat OR of thousands of terms is shallow and still a plan. */
    @Test
    void aFilterWithTooManyPredicatesIsRejected() {
        List<Map<String, Object>> terms = new ArrayList<>();
        for (int i = 0; i < Filter.MAX_NODES + 1; i++) {
            terms.add(Map.of("session_name", "s" + i));
        }
        Map<String, Object> wide = Map.of("OR", terms);

        assertThatThrownBy(() -> Filter.parse(wide))
                .isInstanceOf(FilterException.class)
                .hasMessageContaining("predicates");
    }

    /** The limits have to leave room for a filter a person would actually write. */
    @Test
    void anOrdinaryNestedFilterStillParses() {
        Map<String, Object> filter =
                Map.of(
                        "level", "explicit",
                        "OR", List.of(Map.of("session_name", "s1"), Map.of("session_name", "s2")),
                        "NOT", Map.of("sync_state", "failed"));

        assertThat(Filter.parse(filter)).isInstanceOf(Filter.And.class);
    }
}
