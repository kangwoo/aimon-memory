package at.aimon.memory.core.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import at.aimon.memory.core.MemoryException;

class KeysTest {

    @Test
    void pairKeyRoundTrips() {
        PairKey pair = new PairKey("ws", "alice", "bob");
        assertThat(PairKey.parse(pair.encode())).isEqualTo(pair);
    }

    /**
     * Peer names are user input, so they contain the delimiter sooner or later. A key that cannot
     * survive a colon files memories under a pair that does not exist.
     */
    @ParameterizedTest
    @ValueSource(strings = {"a:b", "100%", "a:b:c", "%3A", "%25", "::"})
    void keySegmentsSurviveDelimitersAndEscapes(String awkward) {
        PairKey pair = new PairKey("ws", awkward, "bob");
        assertThat(PairKey.parse(pair.encode())).isEqualTo(pair);

        WorkUnitKey unit = new WorkUnitKey(TaskType.REPRESENTATION, "ws", awkward, awkward, "bob");
        assertThat(WorkUnitKey.parse(unit.encode())).isEqualTo(unit);
    }

    @Test
    void workUnitKeyWithoutSessionRoundTrips() {
        WorkUnitKey dream = WorkUnitKey.dream(new PairKey("ws", "alice", "alice"));
        assertThat(dream.sessionName()).isNull();

        WorkUnitKey parsed = WorkUnitKey.parse(dream.encode());
        assertThat(parsed).isEqualTo(dream);
        assertThat(parsed.session()).isEmpty();
    }

    @Test
    void representationKeysDifferPerPair() {
        PairKey self = PairKey.self("ws", "alice");
        PairKey observed = new PairKey("ws", "bob", "alice");

        // Two observers of the same message must not serialise onto one work unit, or one batch's
        // dedup would run against the other's conclusions.
        assertThat(WorkUnitKey.representation("ws", "s1", self).encode())
                .isNotEqualTo(WorkUnitKey.representation("ws", "s1", observed).encode());
    }

    @Test
    void blankSegmentsAreRejected() {
        assertThatThrownBy(() -> new PairKey("ws", " ", "bob")).isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> PairKey.parse("ws:alice")).isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> WorkUnitKey.parse("nope:ws::a:b")).isInstanceOf(MemoryException.class);
    }

    @Test
    void selfPairIsRecognised() {
        assertThat(PairKey.self("ws", "alice").isSelfPair()).isTrue();
        assertThat(new PairKey("ws", "alice", "bob").isSelfPair()).isFalse();
    }
}
