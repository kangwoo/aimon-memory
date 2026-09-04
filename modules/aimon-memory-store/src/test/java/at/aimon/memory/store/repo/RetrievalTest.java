package at.aimon.memory.store.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.memory.core.filter.Filter;
import at.aimon.memory.core.filter.FilterOp;
import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.EventType;
import at.aimon.memory.store.Drafts;
import at.aimon.memory.store.StoreTestBase;

class RetrievalTest extends StoreTestBase {

    private PairKey pair;

    @BeforeEach
    void seed() {
        pair = seedPair("alice", "alice");
        seedSession("s1");
        conclusions.upsert(Drafts.explicit(pair, "s1", "alice works at a bank in seoul"));
        conclusions.upsert(Drafts.explicit(pair, "s1", "alice enjoys hiking in the mountains"));
        conclusions.upsert(Drafts.explicit(pair, "s1", "bob prefers tea over coffee"));
    }

    @Test
    void semanticSearchRanksByCosineSimilarity() {
        // The stub embedder is lexical, so the query is phrased in the store's own vocabulary. This
        // test is about the SQL and the distance-to-similarity mapping, not about embedding quality.
        var hits = conclusions.semantic(pair, Drafts.embed("bank seoul"), 10, Filter.ALL);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).conclusion().content()).contains("bank");
        assertThat(hits).allSatisfy(hit -> assertThat(hit.score()).isBetween(0.0, 1.0));
        // Ordering must be non-increasing; the SQL orders by distance ascending.
        for (int i = 1; i < hits.size(); i++) {
            assertThat(hits.get(i).score()).isLessThanOrEqualTo(hits.get(i - 1).score());
        }
    }

    /**
     * Query terms are ORed, not ANDed.
     *
     * <p>{@code plainto_tsquery} would require every term to appear, which turns ranking into
     * filtering and drops exactly the candidates fusion exists to reorder.
     */
    @Test
    void keywordSearchOrsTermsAndScoresWithBm25() {
        var hits = conclusions.keyword(pair, Drafts.analyze("bank mountains"), 10, Filter.ALL);

        assertThat(hits).hasSize(2);
        assertThat(hits).allSatisfy(hit -> assertThat(hit.score()).isGreaterThan(0.0));
        for (int i = 1; i < hits.size(); i++) {
            assertThat(hits.get(i).score()).isLessThanOrEqualTo(hits.get(i - 1).score());
        }
    }

    /**
     * Regression: the candidate query used to be {@code LIMIT n} with no ORDER BY, so Postgres was
     * free to return any matching rows. On a pair with more matches than the limit the best BM25 hit
     * could be missed entirely, and which rows came back could change between runs. Every test corpus
     * was smaller than the limit, so nothing caught it.
     */
    @Test
    void keywordSearchPicksTheBestCandidatesWhenThereAreMoreMatchesThanTheLimit() {
        // Twenty rows mention "bank"; exactly one also mentions the rare term.
        for (int i = 0; i < 20; i++) {
            conclusions.upsert(Drafts.explicit(pair, "s1", "alice visited a bank branch number " + i));
        }
        conclusions.upsert(Drafts.explicit(pair, "s1", "alice works at a bank in gangnam"));

        for (int run = 0; run < 5; run++) {
            var hits = conclusions.keyword(pair, Drafts.analyze("gangnam bank"), 3, Filter.ALL);
            assertThat(hits).hasSize(3);
            assertThat(hits.get(0).conclusion().content())
                    .as("the row matching the rare term must survive candidate selection, run %d", run)
                    .contains("gangnam");
        }
    }

    /**
     * Same answer regardless of how the rows are laid out on disk.
     *
     * <p>Simply running the query twice would prove nothing: on a small table Postgres reads the heap
     * in the same physical order both times, so an unordered query looks perfectly stable. The rows
     * are rewritten and vacuumed between runs to move them, which is the condition under which an
     * arbitrary selection actually starts returning something different.
     */
    @Test
    void keywordCandidateSelectionSurvivesRowsMovingOnDisk() {
        for (int i = 0; i < 60; i++) {
            conclusions.upsert(Drafts.explicit(pair, "s1", "alice visited a bank branch number " + i));
        }
        List<String> before = conclusions.keyword(pair, Drafts.analyze("bank"), 5, Filter.ALL).stream().map(h -> h.id())
                .toList();
        assertThat(before).hasSize(5);

        // An UPDATE writes a new tuple version for every row, so the heap order is no longer insertion
        // order; VACUUM then frees the old ones and lets a later scan see a different arrangement.
        jdbc.sql("UPDATE conclusions SET updated_at = now() WHERE workspace_name = ?").param(WORKSPACE).update();
        jdbc.sql("VACUUM ANALYZE conclusions").update();

        assertThat(conclusions.keyword(pair, Drafts.analyze("bank"), 5, Filter.ALL)).extracting(h -> h.id())
                .isEqualTo(before);
    }

    /**
     * Regression: byIds returned soft-deleted rows, so a deleted fact came back through the entity
     * provenance walk and the reasoning chain — the two responses that carry no deleted marker.
     */
    @Test
    void byIdsExcludesSoftDeletedRows() {
        List<String> ids = conclusions.list(pair, Filter.ALL, 0, 10).items().stream().map(c -> c.id()).toList();
        assertThat(conclusions.byIds(WORKSPACE, ids)).hasSize(3);

        conclusions.softDelete(WORKSPACE, ids.get(0), Actor.API, Map.of(), EventType.DELETE);

        assertThat(conclusions.byIds(WORKSPACE, ids)).hasSize(2).extracting(c -> c.id()).doesNotContain(ids.get(0));
        // find() still resolves it, because the audit and restore paths need the row.
        assertThat(conclusions.find(WORKSPACE, ids.get(0))).isPresent();
    }

    @Test
    void keywordSearchIsEmptyWhenNothingMatches() {
        assertThat(conclusions.keyword(pair, Drafts.analyze("zeppelin"), 10, Filter.ALL)).isEmpty();
        assertThat(conclusions.keyword(pair, "", 10, Filter.ALL)).isEmpty();
        // A query of pure punctuation sanitises to nothing and must not become a syntax error.
        assertThat(conclusions.keyword(pair, "!!! &&& |||", 10, Filter.ALL)).isEmpty();
    }

    @Test
    void bothPathsRespectTheFilter() {
        Filter onlyOtherSession = new Filter.Cmp("session_name", FilterOp.EQ, "s2");
        assertThat(conclusions.semantic(pair, Drafts.embed("bank"), 10, onlyOtherSession)).isEmpty();
        assertThat(conclusions.keyword(pair, Drafts.analyze("bank"), 10, onlyOtherSession)).isEmpty();
    }

    /** Pair isolation is structural: there is no query in the repository that omits the pair. */
    @Test
    void anotherPairSeesNothing() {
        PairKey other = seedPair("bob", "bob");
        assertThat(conclusions.semantic(other, Drafts.embed("bank"), 10, Filter.ALL)).isEmpty();
        assertThat(conclusions.keyword(other, Drafts.analyze("bank"), 10, Filter.ALL)).isEmpty();
    }

    @Test
    void deletedAndExpiredRowsAreInvisible() {
        var hit = conclusions.semantic(pair, Drafts.embed("bank"), 1, Filter.ALL).get(0);
        conclusions.softDelete(WORKSPACE, hit.id(), Actor.API, Map.of(), EventType.DELETE);

        assertThat(conclusions.semantic(pair, Drafts.embed("bank"), 10, Filter.ALL)).extracting(h -> h.id())
                .doesNotContain(hit.id());

        jdbc.sql("UPDATE conclusions SET expires_at = now() - interval '1 day' WHERE deleted_at IS NULL").update();
        assertThat(conclusions.semantic(pair, Drafts.embed("alice"), 10, Filter.ALL)).isEmpty();
    }

    /**
     * A candidate found by only one path still has a real score on the other signal. Without this,
     * the ranking would depend on which path happened to surface a row first.
     */
    @Test
    void semanticScoresCanBeFetchedForArbitraryIds() {
        List<String> ids = conclusions.list(pair, Filter.ALL, 0, 10).items().stream().map(c -> c.id()).toList();
        Map<String, Double> scores = conclusions.semanticScores(pair, Drafts.embed("bank in seoul"), ids);

        assertThat(scores).hasSize(3);
        assertThat(scores.values()).allSatisfy(score -> assertThat(score).isBetween(0.0, 1.0));
    }

    /**
     * Marked, never deleted. Forgetting a user cannot see is indistinguishable from data loss, so the
     * system reports candidates and leaves the decision to a person.
     */
    @Test
    void archiveCandidatesAreOnlyStaleUnreferencedSingletons() {
        java.time.Instant cutoff = java.time.Instant.now().plusSeconds(60);

        // Everything here is derived once and referenced by nothing, so all three qualify.
        assertThat(conclusions.archiveCandidates(pair, cutoff, 10)).hasSize(3);

        // Reinforcing one disqualifies it: it is still being talked about.
        conclusions.upsert(Drafts.explicit(pair, "s1", "alice works at a bank in seoul"));
        assertThat(conclusions.archiveCandidates(pair, cutoff, 10)).extracting(c -> c.content())
                .doesNotContain("alice works at a bank in seoul");

        // Something else resting on it disqualifies it too: deleting it would break a reasoning chain.
        String hiking = conclusions.list(pair, Filter.ALL, 0, 10).items().stream()
                .filter(c -> c.content().contains("hiking")).findFirst().orElseThrow().id();
        jdbc.sql("UPDATE conclusions SET source_ids = ?::jsonb WHERE content LIKE 'bob%'").param("[\"" + hiking + "\"]")
                .update();
        assertThat(conclusions.archiveCandidates(pair, cutoff, 10)).extracting(c -> c.content())
                .doesNotContain("alice enjoys hiking in the mountains");

        // Nothing is stale yet by the real clock.
        assertThat(conclusions.archiveCandidates(pair, java.time.Instant.EPOCH, 10)).isEmpty();
    }

    @Test
    void corpusStatisticsReflectOnlyLiveRowsInThePair() {
        var stats = conclusions.corpusStats(pair, List.of("alice", "bank"));
        assertThat(stats.documentCount()).isEqualTo(3);
        assertThat(stats.averageLength()).isGreaterThan(0.0);
        assertThat(stats.documentFrequency().get("alice")).isEqualTo(2L);
        assertThat(stats.documentFrequency().get("bank")).isEqualTo(1L);
    }
}
