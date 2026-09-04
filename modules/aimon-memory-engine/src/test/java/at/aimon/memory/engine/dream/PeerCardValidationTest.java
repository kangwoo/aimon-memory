package at.aimon.memory.engine.dream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PeerCardValidationTest {

    /**
     * The prefix constraint is not decoration. It forces the model to classify each line, and it makes
     * the card mechanically checkable — a malformed generation is dropped rather than stored and later
     * read back as fact.
     */
    @Test
    void keepsOnlyPrefixedLines() {
        List<String> validated = PeerCardService.validate(List.of("IDENTITY: alice, software engineer",
                "she seems nice", "ATTRIBUTE: lives in Seoul", "", "  RELATIONSHIP: works with bob  ",
                "TODO: ask about this", "INSTRUCTION: prefers short answers"));

        assertThat(validated).containsExactly("IDENTITY: alice, software engineer", "ATTRIBUTE: lives in Seoul",
                "RELATIONSHIP: works with bob", "INSTRUCTION: prefers short answers");
    }

    @Test
    void capsTheCardAtFortyLines() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            many.add("ATTRIBUTE: fact " + i);
        }
        assertThat(PeerCardService.validate(many)).hasSize(PeerCardService.MAX_LINES);
    }

    @Test
    void toleratesNullsAndEmptyInput() {
        List<String> withNulls = new ArrayList<>();
        withNulls.add(null);
        withNulls.add("IDENTITY: alice");
        assertThat(PeerCardService.validate(withNulls)).containsExactly("IDENTITY: alice");
        assertThat(PeerCardService.validate(List.of())).isEmpty();
    }
}
