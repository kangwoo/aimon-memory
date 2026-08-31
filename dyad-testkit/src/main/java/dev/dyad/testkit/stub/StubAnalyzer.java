package dev.dyad.testkit.stub;

import dev.dyad.core.spi.Analyzer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Whitespace analysis.
 *
 * <p>For tests whose subject is not the analyzer. Running Nori inside a dedup fixture would make the
 * expected values depend on a dictionary version, which is a reliable way to get a flaky suite.
 */
public final class StubAnalyzer implements Analyzer {

    @Override
    public String analyze(String text) {
        return String.join(" ", tokens(text));
    }

    @Override
    public List<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    @Override
    public String languageTag() {
        return "und";
    }
}
