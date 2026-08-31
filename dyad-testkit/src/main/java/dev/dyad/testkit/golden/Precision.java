package dev.dyad.testkit.golden;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Six-decimal comparison for ranking assertions.
 *
 * <p>Six is not arbitrary. Below that, an ordering change between two close candidates hides inside
 * the tolerance; above it, the last bits of IEEE-754 arithmetic make the test fail on a different
 * CPU. Six digits catches a changed weight and ignores a changed instruction set.
 */
public final class Precision {

    public static final int SCALE = 6;

    private Precision() {}

    public static double round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    public static void assertMatches(String label, double actual, JsonNode expected) {
        assertThat(expected.isNumber())
                .as("%s: fixture value is missing or not a number", label)
                .isTrue();
        assertThat(round(actual))
                .as("%s", label)
                .isEqualTo(round(expected.asDouble()));
    }
}
