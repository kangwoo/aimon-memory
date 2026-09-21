package at.aimon.memory.engine.dialectic;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.memory.testkit.eval.EvaluationSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Structural checks on the Tier 2 evaluation set.
 *
 * <p>Whether an answer is grounded or merely plausible is a judgement, and a scripted model scoring
 * itself proves nothing — so the answers are graded by a person against the rubric, using the sheet
 * {@code ./gradlew dialecticSheet} produces. What is checked here is that the set itself stays
 * usable: an evaluation set that quietly loses its abstention cases still runs, still reports a
 * score, and no longer measures the thing it was built for.
 */
class DialecticEvaluationSetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode set() {
        try {
            return MAPPER.readTree(
                    Files.readString(EvaluationSet.directory().resolve("dialectic.json"), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void everyFailureModeTheDialecticPromptTargetsIsCovered() {
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        set().path("queries").forEach(q -> byCategory.merge(q.path("category").asString(), 1, Integer::sum));

        // One category per behaviour the system prompt asks for. Dropping any of them leaves that
        // instruction untested while the suite still reports a number.
        assertThat(byCategory.keySet()).containsExactlyInAnyOrder("enumeration", "update", "contradiction",
                "abstention", "provenance");
        assertThat(byCategory.values()).allSatisfy(count -> assertThat(count).isGreaterThanOrEqualTo(5));
        assertThat(set().path("queries").size()).isGreaterThanOrEqualTo(30);
    }

    @Test
    void everyQueryStatesWhatWouldCountAsPassing() {
        List<String> ids = new ArrayList<>();
        set().path("queries").forEach(q -> {
            assertThat(q.path("query").asString()).as("query text").isNotBlank();
            // Without this, scoring drifts to whatever the reader thinks a good answer is.
            assertThat(q.path("expect").asString()).as("%s expectation", q.path("id").asString()).isNotBlank();
            ids.add(q.path("id").asString());
        });
        assertThat(ids).doesNotHaveDuplicates();
    }

    /** Grounding and abstention have to outweigh everything else, or a fluent fabrication scores well. */
    @Test
    void theRubricWeightsGroundingAboveStyle() {
        Map<String, Integer> weights = new LinkedHashMap<>();
        set().path("rubric").path("criteria")
                .forEach(c -> weights.put(c.path("id").asString(), c.path("weight").asInt()));

        assertThat(weights).containsKeys("grounded", "abstained", "complete", "current", "conflict");
        assertThat(weights.get("grounded")).isGreaterThan(weights.get("direct"));
        assertThat(weights.get("abstained")).isGreaterThan(weights.get("cited"));
        set().path("rubric").path("criteria").forEach(c -> assertThat(c.path("question").asString()).isNotBlank());
    }
}
