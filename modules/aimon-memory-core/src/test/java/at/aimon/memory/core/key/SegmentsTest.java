package at.aimon.memory.core.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.MemoryException;

/**
 * The key escaping, at the edges {@code KeysTest} cannot reach.
 *
 * <p>{@code KeysTest} round-trips whole keys, which covers what encode and decode do to each other and
 * nothing about what decode does to input it did not produce. That input is real: {@code work_unit_key}
 * is a text column, and every value read back out of it goes through {@code decode} — a row written by
 * an older build, edited by hand, or truncated by a column narrower than the key arrives here.
 */
class SegmentsTest {

    @Test
    void onlyThePercentAndTheDelimiterAreEscaped() {
        assertThat(Segments.encode("alice")).isEqualTo("alice");
        assertThat(Segments.encode("a:b")).isEqualTo("a%3Ab");
        assertThat(Segments.encode("100%")).isEqualTo("100%25");
        // Everything else survives verbatim, which is what keeps a key readable in a log.
        assertThat(Segments.encode("서울 강남/한글-_.")).isEqualTo("서울 강남/한글-_.");
    }

    /**
     * The percent is escaped before the colon, so an already-escaped-looking name round-trips.
     *
     * <p>A name of literally {@code %3A} must not decode back to a colon; if encode emitted it
     * unchanged, two different peers would share one key and their memories would serialise onto the
     * same work unit.
     */
    @Test
    void anAlreadyEscapedLookingNameIsNotConfusedForTheEscape() {
        assertThat(Segments.encode("%3A")).isEqualTo("%253A");
        assertThat(Segments.decode(Segments.encode("%3A"))).isEqualTo("%3A");
        assertThat(Segments.decode(Segments.encode("%25"))).isEqualTo("%25");
    }

    @Test
    void encodingNullYieldsAnEmptySegment() {
        // WorkUnitKey.encode leans on this for the absent session of a pair-wide task.
        assertThat(Segments.encode(null)).isEmpty();
    }

    @Test
    void decodingLeavesInputWithoutEscapesAlone() {
        assertThat(Segments.decode("alice")).isEqualTo("alice");
        assertThat(Segments.decode("")).isEmpty();
    }

    /** Lowercase hex decodes too, so a key written by anything less fussy still parses. */
    @Test
    void theColonEscapeIsCaseInsensitive() {
        assertThat(Segments.decode("a%3Ab")).isEqualTo("a:b");
        assertThat(Segments.decode("a%3ab")).isEqualTo("a:b");
    }

    @Test
    void severalEscapesInOneSegmentAllDecode() {
        assertThat(Segments.decode("%3A%25%3A")).isEqualTo(":%:");
    }

    /**
     * A truncated escape is refused rather than read as a literal percent.
     *
     * <p>Reachable from a column that cut the key short. Failing loudly is the right answer: the
     * alternative is a key that silently differs from the one that was written, which is a work unit
     * nothing will ever claim.
     */
    @Test
    void aTruncatedEscapeIsRejected() {
        assertThatThrownBy(() -> Segments.decode("alice%")).isInstanceOf(MemoryException.class)
                .hasMessageContaining("truncated");
        assertThatThrownBy(() -> Segments.decode("alice%3")).isInstanceOf(MemoryException.class)
                .hasMessageContaining("truncated");
    }

    /** Only the two escapes this scheme emits are accepted; anything else is a key it did not write. */
    @Test
    void anUnsupportedEscapeIsRejected() {
        assertThatThrownBy(() -> Segments.decode("alice%2Fbob")).isInstanceOf(MemoryException.class)
                .hasMessageContaining("unsupported escape");
    }

    @Test
    void requiredRejectsNullAndBlankAndNamesTheField() {
        assertThatThrownBy(() -> Segments.required(null, "observer")).isInstanceOf(MemoryException.class)
                .hasMessageContaining("observer");
        assertThatThrownBy(() -> Segments.required("   ", "observer")).isInstanceOf(MemoryException.class);
        assertThat(Segments.required("alice", "observer")).isEqualTo("alice");
    }
}
