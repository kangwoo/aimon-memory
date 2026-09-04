package at.aimon.memory.recall;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.ConclusionDraft;
import at.aimon.memory.core.model.ConclusionLevel;
import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.testkit.eval.EvaluationSet;
import at.aimon.memory.testkit.eval.RankingMetrics;
import at.aimon.memory.text.ContentHash;
import at.aimon.memory.text.KoreanTextAnalyzer;
import at.aimon.memory.text.Normalizer;

/**
 * The ranking quality gate — the one the golden fixtures deliberately cannot be.
 *
 * <p>A fixture proves the formula is implemented as written. It cannot notice that a weight is wrong,
 * because a wrong weight produces a consistent answer that the fixture faithfully records. Graded
 * relevance judgements are the only thing that can tell a correct implementation of a bad formula
 * from a correct implementation of a good one.
 *
 * <p><b>What this measures, honestly.</b> The corpus is real Korean text with real judgements, and
 * the keyword, entity, reinforcement, recency and level signals are all genuine. The semantic signal
 * is not: without provider credentials the embedder is a lexical stand-in, so {@code sem} behaves
 * like a second keyword signal. The absolute numbers therefore say nothing about how the system
 * performs with real embeddings. What they do catch is a regression — a weight change, a broken
 * signal, a retrieval path quietly dropping candidates — which is exactly what a gate is for.
 *
 * <p>Rerun with {@code -Daimon.memory.eval.update=true} to rewrite the baseline, and read the diff.
 */
class RankingEvaluationTest extends RecallTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Room for floating-point noise, and nothing else. A real regression clears this easily. */
    private static final double TOLERANCE = 1e-6;

    private EvaluationSet set;
    private PairKey pair;
    private KoreanTextAnalyzer korean;

    @BeforeEach
    void seedCorpus() {
        set = EvaluationSet.load("ranking");
        set.validate();

        korean = new KoreanTextAnalyzer();
        pair = seedPair("alice", "alice");
        seedSession("s1");
        workspaces.updateConfiguration(WORKSPACE, Map.of("language", "ko"));
        settings.invalidate(WORKSPACE);

        Map<String, String> storedIds = new LinkedHashMap<>();
        for (EvaluationSet.Document document : set.corpus()) {
            storedIds.put(document.id(), store(document, storedIds));
        }
        this.labelToStoredId = Map.copyOf(storedIds);
    }

    private Map<String, String> labelToStoredId;

    private String store(EvaluationSet.Document document, Map<String, String> alreadyStored) {
        ConclusionLevel level = ConclusionLevel.fromWire(document.level());
        String norm = Normalizer.normalize(document.content());
        List<String> sources = document.sourceIds().stream().map(alreadyStored::get).filter(java.util.Objects::nonNull)
                .toList();

        String id = conclusions.upsert(ConclusionDraft.builder().pair(pair)
                .sessionName(level == ConclusionLevel.EXPLICIT ? "s1" : null).content(document.content())
                .contentNorm(norm).contentAnalyzed(korean.analyze(document.content())).contentHash(ContentHash.of(norm))
                .level(level).confidence(document.confidence()).sourceIds(sources)
                .embedding(embedder.embed(document.content(), EmbedPurpose.DOCUMENT)).actor(Actor.DERIVER).build())
                .conclusionId();

        setRankingState(id, document.timesDerived(), NOW.minus(Duration.ofDays(document.daysAgo())));
        document.entities().forEach(entity -> linkEntity(pair, id, entity));
        return id;
    }

    @Test
    void rankingQualityHasNotRegressed() {
        List<ObjectNode> perQuery = new ArrayList<>();
        double ndcg5 = 0;
        double ndcg10 = 0;
        double mrr = 0;
        double recall10 = 0;

        for (EvaluationSet.Query query : set.queries()) {
            Map<String, Integer> judgements = translate(query.judgements());
            List<String> ranked = recall.recall(new RecallRequest(pair, query.query(), 10, null, null, false)).hits()
                    .stream().map(hit -> hit.id()).toList();

            double q5 = RankingMetrics.ndcg(ranked, judgements, 5);
            double q10 = RankingMetrics.ndcg(ranked, judgements, 10);
            double rr = RankingMetrics.reciprocalRank(ranked, judgements);
            double r10 = RankingMetrics.recall(ranked, judgements, 10);

            ndcg5 += q5;
            ndcg10 += q10;
            mrr += rr;
            recall10 += r10;

            ObjectNode node = MAPPER.createObjectNode();
            node.put("id", query.id());
            node.put("query", query.query());
            node.put("ndcg@5", round(q5));
            node.put("ndcg@10", round(q10));
            node.put("rr", round(rr));
            node.put("recall@10", round(r10));
            perQuery.add(node);
        }

        int n = set.queries().size();
        ObjectNode report = MAPPER.createObjectNode();
        report.put("corpus", set.fingerprint());
        ObjectNode mean = report.putObject("mean");
        mean.put("ndcg@5", round(ndcg5 / n));
        mean.put("ndcg@10", round(ndcg10 / n));
        mean.put("mrr", round(mrr / n));
        mean.put("recall@10", round(recall10 / n));
        report.put("queries", n);
        report.set("perQuery", MAPPER.valueToTree(perQuery));

        if (updateMode()) {
            writeBaseline(report);
            System.out.println(prettyReport(report));
            return;
        }

        JsonNode baseline = readBaseline();
        System.out.println(prettyReport(report));

        // The corpus the floor was measured on, before any comparison against it.
        //
        // A one-sided gate can only report on a change if everything else held still. Regenerating the
        // baseline in the same commit that changed the ent signal — while the corpus grew from 26
        // documents to 40 and the queries from 16 to 50 — made "did this help or hurt" unanswerable and
        // the gate green regardless. Expanding the corpus is fine; doing it in the same change as the
        // behaviour under measurement is not, and this is what says so out loud.
        assertThat(baseline.path("corpus").asText())
                .as("the baseline was measured on a different corpus. Expand the corpus and"
                        + " regenerate the baseline (-Daimon.memory.eval.update=true) in a change that"
                        + " alters nothing else, then make the ranking change on top of it")
                .isEqualTo(set.fingerprint());

        // One-sided: improvements pass and are expected to move the baseline on the next update run.
        for (String metric : List.of("ndcg@5", "ndcg@10", "mrr", "recall@10")) {
            assertThat(mean.path(metric).asDouble()).as("mean %s must not fall below the baseline", metric)
                    .isGreaterThanOrEqualTo(baseline.path("mean").path(metric).asDouble() - TOLERANCE);
        }

        // Per query as well as in aggregate: a mean can hide one query collapsing while others improve.
        Map<String, JsonNode> baselineByQuery = new LinkedHashMap<>();
        baseline.path("perQuery").forEach(node -> baselineByQuery.put(node.path("id").asText(), node));
        for (ObjectNode node : perQuery) {
            JsonNode was = baselineByQuery.get(node.path("id").asText());
            if (was != null) {
                assertThat(node.path("ndcg@10").asDouble())
                        .as("%s (%s) nDCG@10", node.path("id").asText(), node.path("query").asText())
                        .isGreaterThanOrEqualTo(was.path("ndcg@10").asDouble() - TOLERANCE);
            }
        }
    }

    /** Every judged document must be findable at all, or the gate is measuring an empty index. */
    @Test
    void everyJudgedDocumentIsRetrievableByItsOwnText() {
        for (EvaluationSet.Document document : set.corpus()) {
            List<String> ranked = recall.recall(new RecallRequest(pair, document.content(), 5, null, null, false))
                    .hits().stream().map(hit -> hit.id()).toList();
            assertThat(ranked).as("'%s' should retrieve itself", document.content())
                    .contains(labelToStoredId.get(document.id()));
        }
    }

    private Map<String, Integer> translate(Map<String, Integer> judgements) {
        Map<String, Integer> out = new LinkedHashMap<>();
        judgements.forEach((label, grade) -> out.put(labelToStoredId.get(label), grade));
        return out;
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    private static boolean updateMode() {
        return Boolean.parseBoolean(System.getProperty("aimon.memory.eval.update", "false"));
    }

    private static Path baselinePath() {
        return EvaluationSet.directory().resolve("ranking-baseline.json");
    }

    private static JsonNode readBaseline() {
        Path file = baselinePath();
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(
                    "no baseline at " + file + "; run with -Daimon.memory.eval.update=true to create it");
        }
        try {
            return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeBaseline(JsonNode report) {
        try {
            Files.createDirectories(baselinePath().getParent());
            Files.writeString(baselinePath(), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String prettyReport(JsonNode report) {
        StringBuilder sb = new StringBuilder("\nranking evaluation\n");
        sb.append("  mean nDCG@5   ").append(report.path("mean").path("ndcg@5").asDouble()).append('\n');
        sb.append("  mean nDCG@10  ").append(report.path("mean").path("ndcg@10").asDouble()).append('\n');
        sb.append("  mean MRR      ").append(report.path("mean").path("mrr").asDouble()).append('\n');
        sb.append("  mean recall@10").append("  ").append(report.path("mean").path("recall@10").asDouble())
                .append('\n');
        sb.append("  weakest queries:\n");
        List<JsonNode> sorted = new ArrayList<>();
        report.path("perQuery").forEach(sorted::add);
        sorted.sort((a, b) -> Double.compare(a.path("ndcg@10").asDouble(), b.path("ndcg@10").asDouble()));
        sorted.stream().limit(5)
                .forEach(node -> sb.append("    ").append(String.format("%.3f", node.path("ndcg@10").asDouble()))
                        .append("  ").append(node.path("query").asText()).append('\n'));
        return sb.toString();
    }
}
