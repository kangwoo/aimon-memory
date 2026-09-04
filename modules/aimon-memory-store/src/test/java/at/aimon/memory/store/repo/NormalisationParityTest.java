package at.aimon.memory.store.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import at.aimon.memory.store.StoreTestBase;
import at.aimon.memory.text.Normalizer;

/**
 * The Java normalisation rule and its SQL twin must agree, character for character.
 *
 * <p>{@code content_norm} is written by Java and compared by SQL. If the two ever diverge, dedup
 * stage 2 stops matching and the store quietly accumulates duplicates — a failure with no error
 * message that shows up months later as a memory repeated four times.
 */
class NormalisationParityTest extends StoreTestBase {

    @ParameterizedTest
    @ValueSource(strings = {"  Alice Works At A Bank  ", "ALICE", "앨리스는 서울에 산다", "Café RÉSUMÉ",
            "  mixed   INNER   spacing  ", "ıI", "ÅNGSTRÖM", "\ttabbed\n", "123 ABC xyz",})
    void javaAndSqlAgree(String raw) {
        String java = Normalizer.normalize(raw);
        String sql = normaliseInPostgres(raw);
        assertThat(java).as("normalisation of %s", raw).isEqualTo(sql);
    }

    /**
     * Inner whitespace is deliberately not collapsed. Both sides agree on that, which is the point —
     * the rule can be anything as long as it is the same rule twice.
     */
    @ParameterizedTest
    @ValueSource(strings = {"a  b", "a\tb"})
    void innerWhitespaceIsPreservedOnBothSides(String raw) {
        assertThat(Normalizer.normalize(raw)).isEqualTo(normaliseInPostgres(raw));
    }

    /** The stored column has to match what Java would compute for the same content. */
    @org.junit.jupiter.api.Test
    void storedColumnMatchesTheJavaRule() {
        var pair = seedPair("alice", "alice");
        seedSession("s1");
        String content = "  Alice Works At A Bank In SEOUL  ";
        String id = conclusions.upsert(at.aimon.memory.store.Drafts.explicit(pair, "s1", content)).conclusionId();

        List<String> mismatches = jdbc.sql(
                "SELECT id FROM conclusions WHERE content_norm <> " + Normalizer.SQL_EXPRESSION.formatted("content"))
                .query(String.class).list();
        assertThat(mismatches).isEmpty();
        assertThat(conclusions.find(WORKSPACE, id).orElseThrow().contentNorm())
                .isEqualTo(Normalizer.normalize(content));
    }

    private String normaliseInPostgres(String raw) {
        return jdbc.sql("SELECT " + Normalizer.SQL_EXPRESSION.formatted("?") + " AS n").param(raw).query(String.class)
                .single();
    }
}
