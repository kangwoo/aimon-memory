package dev.dyad.recall;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dyad.core.key.PairKey;
import dev.dyad.core.model.ConclusionLevel;
import dev.dyad.core.model.ScoredConclusion;
import dev.dyad.testkit.golden.GoldenFixtures;
import dev.dyad.testkit.golden.Precision;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The I3 gate: every signal and the fused score, to six decimal places, with zero rank inversions.
 *
 * <p>What this proves and what it does not is worth being precise about. It proves the formula is
 * implemented as specified and that nothing has changed it by accident. It says nothing at all about
 * whether the weights are any good — that needs a labelled evaluation set, and conflating the two is
 * how a system ends up with a green suite and bad answers.
 */
class RecallGoldenTest extends RecallTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GoldenFixtures fixtures = GoldenFixtures.standard();
    private PairKey pair;

    @BeforeEach
    void seedCorpus() {
        pair = seedPair("alice", "alice");
        seedSession("s1");

        // Each row is set up to be won by a different signal, so a weight change moves the ordering.
        String bank = store(pair, "s1", "alice works at a bank in seoul gangnam");
        setRankingState(bank, 5, NOW.minus(Duration.ofDays(10)));
        linkEntity(pair, bank, "seoul");

        String hiking = store(pair, "s1", "alice enjoys hiking in the mountains near seoul");
        setRankingState(hiking, 1, NOW.minus(Duration.ofDays(200)));
        linkEntity(pair, hiking, "seoul");

        String coffee = store(pair, "s1", "alice drinks coffee every morning");
        setRankingState(coffee, 12, NOW.minus(Duration.ofDays(1)));

        String pattern =
                store(pair, null, "alice prefers routine over spontaneity", ConclusionLevel.INDUCTIVE, List.of());
        setRankingState(pattern, 2, NOW.minus(Duration.ofDays(30)));
    }

    @Test
    void explainMatchesTheGoldenFixtureToSixDecimals() {
        var response = recall.recall(RecallRequest.of(pair, "seoul bank"));
        assertThat(response.hits()).isNotEmpty();

        if (GoldenFixtures.updateMode()) {
            fixtures.write("recall-seoul-bank", snapshot(response));
            return;
        }

        JsonNode expected = fixtures.load("recall-seoul-bank");
        JsonNode actual = snapshot(response);

        assertThat(actual.path("analyzed_query").asText())
                .isEqualTo(expected.path("analyzed_query").asText());

        ArrayNode expectedHits = (ArrayNode) expected.path("hits");
        ArrayNode actualHits = (ArrayNode) actual.path("hits");
        assertThat(actualHits.size()).as("hit count").isEqualTo(expectedHits.size());

        for (int i = 0; i < expectedHits.size(); i++) {
            JsonNode want = expectedHits.get(i);
            JsonNode got = actualHits.get(i);
            String at = "rank " + i + " (" + want.path("content").asText() + ")";

            // Rank inversions must be zero, so content is compared positionally.
            assertThat(got.path("content").asText()).as("%s: ordering", at).isEqualTo(want.path("content").asText());

            for (String signal : List.of("sem", "kw", "ent", "reinf", "rec", "lvl", "score")) {
                Precision.assertMatches(at + " " + signal, got.path(signal).asDouble(), want.path(signal));
            }
            assertThat(got.path("matched_entities").toString())
                    .as("%s: matched entities", at)
                    .isEqualTo(want.path("matched_entities").toString());
        }
    }

    /** The stored breakdown must actually add up to the stored score. */
    @Test
    void scoreIsTheWeightedSumOfItsSignals() {
        var response = recall.recall(RecallRequest.of(pair, "seoul bank"));
        for (ScoredConclusion hit : response.hits()) {
            assertThat(Precision.round(hit.score()))
                    .as("score of %s", hit.conclusion().content())
                    .isEqualTo(Precision.round(hit.explain().score()));
        }
    }

    /** Identical inputs, identical output — including the tie-break, which orders on id. */
    @Test
    void rankingIsReproducibleAcrossRuns() {
        var first = recall.recall(RecallRequest.of(pair, "seoul bank"));
        var second = recall.recall(RecallRequest.of(pair, "seoul bank"));

        assertThat(snapshot(second).toString()).isEqualTo(snapshot(first).toString());
    }

    private ObjectNode snapshot(RecallResponse response) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("analyzed_query", response.analyzedQuery());
        root.put("candidates_considered", response.candidatesConsidered());
        ArrayNode hits = root.putArray("hits");
        for (ScoredConclusion hit : response.hits()) {
            ObjectNode node = hits.addObject();
            node.put("content", hit.conclusion().content());
            node.put("level", hit.conclusion().level().wire());
            node.put("times_derived", hit.conclusion().timesDerived());
            node.put("sem", Precision.round(hit.explain().sem()));
            node.put("kw", Precision.round(hit.explain().kw()));
            node.put("ent", Precision.round(hit.explain().ent()));
            node.put("reinf", Precision.round(hit.explain().reinf()));
            node.put("rec", Precision.round(hit.explain().rec()));
            node.put("lvl", Precision.round(hit.explain().lvl()));
            node.put("score", Precision.round(hit.score()));
            ArrayNode matched = node.putArray("matched_entities");
            hit.explain().matchedEntities().forEach(matched::add);
        }
        return root;
    }
}
