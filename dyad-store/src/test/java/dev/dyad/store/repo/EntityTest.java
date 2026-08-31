package dev.dyad.store.repo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dyad.core.key.PairKey;
import dev.dyad.store.Drafts;
import dev.dyad.store.StoreTestBase;
import org.junit.jupiter.api.Test;

class EntityTest extends StoreTestBase {

    @Test
    void nodesAreWorkspaceScopedAndCreatedOnce() {
        seedPair("alice", "alice");
        var first = entities.upsert(WORKSPACE, "서울", "PLACE", Drafts.embed("서울"));
        var second = entities.upsert(WORKSPACE, "  서울  ", "PLACE", Drafts.embed("서울"));

        // One node no matter how many pairs mention it — the improvement over per-user entity stores.
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.nameNorm()).isEqualTo("서울");
    }

    /** Different names stay different nodes; merging two entities cannot be undone. */
    @Test
    void distinctNamesStayDistinct() {
        seedPair("alice", "alice");
        var seoul = entities.upsert(WORKSPACE, "서울", null, Drafts.embed("서울"));
        var busan = entities.upsert(WORKSPACE, "부산", null, Drafts.embed("부산"));
        assertThat(busan.id()).isNotEqualTo(seoul.id());
    }

    /** Nodes are shared; isolation lives on the edge. */
    @Test
    void edgesCarryThePairScope() {
        PairKey self = seedPair("alice", "alice");
        PairKey observed = seedPair("bob", "alice");
        seedSession("s1");

        String mine = conclusions.upsert(Drafts.explicit(self, "s1", "alice lives in seoul")).conclusionId();
        String theirs =
                conclusions.upsert(Drafts.explicit(observed, "s1", "alice lives in seoul")).conclusionId();

        var seoul = entities.upsert(WORKSPACE, "서울", "PLACE", Drafts.embed("서울"));
        entities.link(WORKSPACE, seoul.id(), mine, self);
        entities.link(WORKSPACE, seoul.id(), theirs, observed);

        assertThat(entities.linkedConclusionIds(WORKSPACE, seoul.id(), self)).containsExactly(mine);
        assertThat(entities.linkedConclusionIds(WORKSPACE, seoul.id(), observed)).containsExactly(theirs);
    }

    @Test
    void matchReturnsSimilarityAndLinkCount() {
        PairKey pair = seedPair("alice", "alice");
        seedSession("s1");
        String conclusionId =
                conclusions.upsert(Drafts.explicit(pair, "s1", "alice lives in seoul")).conclusionId();
        var seoul = entities.upsert(WORKSPACE, "seoul", "PLACE", Drafts.embed("seoul"));
        entities.link(WORKSPACE, seoul.id(), conclusionId, pair);

        var matches = entities.match(pair, Drafts.embed("seoul"), 5);
        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).entity().id()).isEqualTo(seoul.id());
        assertThat(matches.get(0).similarity()).isGreaterThan(0.9);
        assertThat(matches.get(0).linkedConclusions()).isEqualTo(1);
    }

    /**
     * An orphan node is not merely untidy: it stays in the vector index and can be returned as a
     * top-k match, contributing a boost to nothing.
     */
    @Test
    void orphanNodesAreCollectedWhenTheirLastEdgeGoes() {
        PairKey pair = seedPair("alice", "alice");
        seedSession("s1");
        String conclusionId =
                conclusions.upsert(Drafts.explicit(pair, "s1", "alice lives in seoul")).conclusionId();
        var seoul = entities.upsert(WORKSPACE, "서울", "PLACE", Drafts.embed("서울"));
        entities.link(WORKSPACE, seoul.id(), conclusionId, pair);

        entities.unlinkConclusion(WORKSPACE, conclusionId);
        assertThat(entities.deleteOrphans(WORKSPACE)).isEqualTo(1);
        assertThat(entities.findByNorm(WORKSPACE, "서울")).isEmpty();
    }

    @Test
    void stillReferencedNodesSurviveTheSweep() {
        PairKey pair = seedPair("alice", "alice");
        seedSession("s1");
        String first = conclusions.upsert(Drafts.explicit(pair, "s1", "alice lives in seoul")).conclusionId();
        String second = conclusions.upsert(Drafts.explicit(pair, "s1", "alice works in seoul")).conclusionId();
        var seoul = entities.upsert(WORKSPACE, "서울", "PLACE", Drafts.embed("서울"));
        entities.link(WORKSPACE, seoul.id(), first, pair);
        entities.link(WORKSPACE, seoul.id(), second, pair);

        entities.unlinkConclusion(WORKSPACE, first);
        assertThat(entities.deleteOrphans(WORKSPACE)).isZero();
        assertThat(entities.findByNorm(WORKSPACE, "서울")).isPresent();
    }

    /**
     * Regression: the link count included conclusions that had been soft-deleted, so an entity looked
     * overused after any churn and its countWeight was permanently deflated — silently, and in the
     * direction of returning worse results.
     */
    @Test
    void linkCountsIgnoreDeletedConclusions() {
        PairKey pair = seedPair("alice", "alice");
        seedSession("s1");
        String live = conclusions.upsert(Drafts.explicit(pair, "s1", "alice lives in seoul")).conclusionId();
        String doomed = conclusions.upsert(Drafts.explicit(pair, "s1", "alice worked in seoul")).conclusionId();

        var seoul = entities.upsert(WORKSPACE, "서울", "PLACE", Drafts.embed("서울"));
        entities.link(WORKSPACE, seoul.id(), live, pair);
        entities.link(WORKSPACE, seoul.id(), doomed, pair);
        assertThat(entities.match(pair, Drafts.embed("서울"), 5).get(0).linkedConclusions()).isEqualTo(2);

        conclusions.softDelete(
                WORKSPACE, doomed, dev.dyad.core.model.Actor.API, java.util.Map.of(),
                dev.dyad.core.model.EventType.DELETE);

        var match = entities.match(pair, Drafts.embed("서울"), 5).get(0);
        assertThat(match.linkedConclusions()).isEqualTo(1);
        assertThat(match.countWeight()).isEqualTo(1.0);
    }

    /** Nodes created before an embedder was available are invisible to matching until backfilled. */
    @Test
    void nodesWithoutVectorsCanBeFoundForReindexing() {
        PairKey pair = seedPair("alice", "alice");
        entities.upsert(WORKSPACE, "부산", null, null);
        var pending = entities.withoutEmbedding(WORKSPACE, 10);

        assertThat(pending).extracting(e -> e.nameNorm()).containsExactly("부산");
        assertThat(entities.match(pair, Drafts.embed("부산"), 5)).isEmpty();

        entities.updateEmbedding(pending.get(0).id(), Drafts.embed("부산"));
        assertThat(entities.withoutEmbedding(WORKSPACE, 10)).isEmpty();
        assertThat(entities.match(pair, Drafts.embed("부산"), 5)).hasSize(1);
    }

    /** Deleting the conclusion row must take its edges with it, or the link table leaks. */
    @Test
    void edgesCascadeWhenAConclusionIsHardDeleted() {
        PairKey pair = seedPair("alice", "alice");
        seedSession("s1");
        String conclusionId =
                conclusions.upsert(Drafts.explicit(pair, "s1", "alice lives in seoul")).conclusionId();
        var seoul = entities.upsert(WORKSPACE, "서울", null, Drafts.embed("서울"));
        entities.link(WORKSPACE, seoul.id(), conclusionId, pair);

        jdbc.sql("DELETE FROM conclusions WHERE id = ?").param(conclusionId).update();
        assertThat(entities.entityIdsFor(WORKSPACE, conclusionId)).isEmpty();
    }
}
