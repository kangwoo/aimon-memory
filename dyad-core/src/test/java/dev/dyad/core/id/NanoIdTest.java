package dev.dyad.core.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NanoIdTest {

    @Test
    void generatesValidIdsOfFixedLength() {
        String id = NanoId.generate();
        assertThat(id).hasSize(NanoId.LENGTH);
        assertThat(NanoId.isValid(id)).isTrue();
    }

    @Test
    void doesNotCollideOverAHundredThousandIds() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            assertThat(seen.add(NanoId.generate())).isTrue();
        }
    }

    @Test
    void rejectsWrongLengthAndAlphabet() {
        assertThat(NanoId.isValid(null)).isFalse();
        assertThat(NanoId.isValid("short")).isFalse();
        assertThat(NanoId.isValid("!".repeat(NanoId.LENGTH))).isFalse();
    }
}
