package dev.dyad.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NormalizerTest {

    private final Locale original = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(original);
    }

    @Test
    void trimsAndLowercases() {
        assertThat(Normalizer.normalize("  Alice Works At A Bank  ")).isEqualTo("alice works at a bank");
        assertThat(Normalizer.normalize(null)).isEmpty();
    }

    /**
     * The Turkish dotted-I is the classic way a default locale silently breaks a normalisation rule.
     * If this drifted, dedup stage 2 would stop matching for anyone running with a Turkish locale and
     * nothing else would notice.
     */
    @Test
    void isIndependentOfTheDefaultLocale() {
        Locale.setDefault(Locale.forLanguageTag("tr"));
        assertThat(Normalizer.normalize("ISTANBUL")).isEqualTo("istanbul");
    }

    @Test
    void hashIsStableAndHexEncoded() {
        String hash = ContentHash.of(Normalizer.normalize("Alice works at a bank"));
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(hash).isEqualTo(ContentHash.of("alice works at a bank"));
    }
}
