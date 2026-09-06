package at.aimon.memory.recall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.memory.core.filter.Filter;
import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.ConclusionDraft;
import at.aimon.memory.core.model.ConclusionLevel;
import at.aimon.memory.core.model.CorpusStats;
import at.aimon.memory.core.model.ScoredConclusion;
import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.recall.signal.KeywordSignal;
import at.aimon.memory.text.Bm25;
import at.aimon.memory.text.ContentHash;
import at.aimon.memory.text.EnglishTextAnalyzer;
import at.aimon.memory.text.Normalizer;

/**
 * What keeping the apostrophe does to the two halves of the keyword signal.
 *
 * <p>A keyword score reaches a hit by one of two routes. {@code ConclusionRepository.keyword} scores
 * the rows its {@code tsquery} matched; {@code RecallService.fillMissingKeyword} scores everything
 * else in the candidate set, so that a row the semantic path found is not ranked as though the term
 * were absent. Only the first route went through {@code TsQuery}, and before this change a possessive
 * could not travel it at all — {@code alices} matched nothing, so the rescue route was the only way
 * such a row was ever scored.
 *
 * <p>That made the rescue load-bearing for a whole class of query and hid the fact that the two
 * routes were counting different things. This runs on the English analyzer, which is the only one
 * that emits a possessive as a single token, and asserts they now produce one number.
 */
class RecallPunctuationTest extends RecallTestBase {

    private final EnglishTextAnalyzer english = new EnglishTextAnalyzer();
    private PairKey pair;

    @BeforeEach
    void seedEnglishWorkspace() {
        pair = seedPair("alice", "alice");
        seedSession("s1");
        // `seedPair` created the workspace with an empty configuration, which resolves to the bigram
        // fallback — and that analyzer splits on an apostrophe, so under it this test's subject does
        // not exist. The cache is dropped because it was populated by the write above.
        workspaces.updateConfiguration(WORKSPACE, Map.of("language", "en"));
        settings.invalidate(WORKSPACE);
    }

    private void store(String content) {
        String norm = Normalizer.normalize(content);
        conclusions.upsert(ConclusionDraft.builder().pair(pair).sessionName("s1").content(content).contentNorm(norm)
                .contentAnalyzed(english.analyze(content)).contentHash(ContentHash.of(norm))
                .level(ConclusionLevel.EXPLICIT).entityNames(List.of())
                .embedding(embedder.embed(content, EmbedPurpose.DOCUMENT)).actor(Actor.DERIVER).build());
    }

    /**
     * Both routes score a possessive with the analyzer's token, and agree to the last decimal.
     *
     * <p>The expected value is recomputed here from the parts either route uses — the raw analyzer
     * terms, a whitespace split of {@code content_analyzed}, and the {@code corpusStats} for those
     * same raw terms — and then put through the same sigmoid the ranker applies, because
     * {@code Explain.kw} carries the normalised signal rather than the BM25 total. Every hit has to
     * equal it, whichever route filled it in: the two rows holding
     * {@code alice's} come back through the keyword path now that the term survives sanitising, and
     * the row that holds none of the terms is in the candidate set only because the semantic path put
     * it there, so its score comes from {@code fillMissingKeyword}.
     *
     * <p>The assertion has teeth because the two routes take their terms from different places. If
     * the keyword path were changed to score with the sanitised terms it sends to Postgres rather
     * than the analyzer's, the {@code alice's} rows would score 0 against a document that plainly
     * contains the word, and only these rows would move — the rescue route would go on being right
     * and hiding it.
     */
    @Test
    void bothRoutesScoreAPossessiveWithTheTokenTheAnalyzerProduced() {
        store("alice's bank statement");
        store("alice's hiking notes");
        store("bob prefers tea");

        List<String> queryTerms = english.tokens("alice's");
        assertThat(queryTerms).containsExactly("alice's");

        var response = recall.recall(RecallRequest.of(pair, "alice's"));
        assertThat(response.hits()).hasSize(3);

        CorpusStats stats = conclusions.corpusStats(pair, queryTerms);
        for (ScoredConclusion hit : response.hits()) {
            double raw = Bm25.score(queryTerms, List.of(hit.conclusion().contentAnalyzed().split("\\s+")), stats);
            double expected = raw <= 0.0 ? 0.0 : KeywordSignal.normalise(raw, queryTerms.size());
            assertThat(hit.explain().kw()).as("keyword score for %s", hit.conclusion().content()).isCloseTo(expected,
                    within(1e-12));
        }

        // The two routes are only interesting if they actually disagree about which rows they cover,
        // so pin that they did: two rows carry the term and one does not.
        assertThat(response.hits()).filteredOn(h -> h.explain().kw() > 0.0).hasSize(2)
                .allSatisfy(h -> assertThat(h.conclusion().content()).contains("alice's"));
    }

    /**
     * The possessive row becomes a candidate on the strength of the keyword path alone.
     *
     * <p>This is the one assertion in this file that the fix moves, and arranging for it takes some
     * care, because {@code fillMissingKeyword} is very good at hiding the bug. On a corpus the
     * semantic path covers completely — which is every other test in this module — the possessive row
     * is in the candidate set no matter what {@code TsQuery} sent, and the rescue then scores it with
     * the unstripped term and gets the right number. The keyword path failing is invisible.
     *
     * <p>So the semantic path is starved: {@code recall.oversample} of 1 and a limit of 1 make it
     * fetch exactly one row, and the decoy is built to be that row. The decoy's bigram tokens are
     * {@code [s, alice]}, the same bag the query {@code alice's} produces, so its vector is identical
     * to the query's and it is nearest by construction rather than by luck — asserted below, so that
     * a change to the stub embedder fails here loudly instead of quietly turning this back into the
     * covered case. Its word order is reversed because {@code 'alice' <-> 's'} is a phrase and wants
     * them adjacent in that order, which keeps the decoy out of the keyword result.
     *
     * <p>What is left is a corpus where the row holding {@code alice's} can only arrive through the
     * keyword path, and the candidate count is what says whether it did. Before the fix it did not:
     * the query became {@code alices}, matched nothing, and one row was considered instead of two.
     *
     * <p>The assertion stops at candidacy on purpose. The decoy is a perfect semantic match and the
     * fused score is weighted towards {@code sem}, so it takes the single result slot — which is the
     * ranker doing its job with the candidates it was given, and belongs to {@code RecallGoldenTest}
     * rather than here. What this file is responsible for is that the keyword path stops silently
     * contributing nothing.
     */
    @Test
    void aPossessiveRowIsFoundWithoutHelpFromTheSemanticPath() {
        workspaces.updateConfiguration(WORKSPACE, Map.of("language", "en", "recall.oversample", 1));
        settings.invalidate(WORKSPACE);

        store("s alice");
        store("alice's bank statement");

        // The setup, asserted rather than assumed: the decoy is the single nearest row, so the one
        // slot the semantic path has is spent on it.
        var nearest = conclusions.semantic(pair, embedder.embed("alice's", EmbedPurpose.QUERY), 1, Filter.ALL);
        assertThat(nearest).singleElement()
                .satisfies(hit -> assertThat(hit.conclusion().content()).isEqualTo("s alice"));

        var response = recall.recall(new RecallRequest(pair, "alice's", 1, Filter.ALL, null, true));

        assertThat(response.candidatesConsidered())
                .as("the possessive row reaches the candidate set through the keyword path alone").isEqualTo(2);
    }
}
