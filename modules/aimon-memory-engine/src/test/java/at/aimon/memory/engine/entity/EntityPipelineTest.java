package at.aimon.memory.engine.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.ConclusionLevel;
import at.aimon.memory.engine.MemoryTestBase;
import at.aimon.memory.engine.derive.ConclusionWriter;

/**
 * Entity names that cannot identify anything never become nodes.
 *
 * <p>This is the choke point every name passes through — the injection endpoint, the deriver and the
 * dreamer all reach {@code linkAll} — so it is the only place a fix covers all three. The HTTP
 * endpoint additionally refuses a blank name outright; these tests are about what happens to the
 * names that no request can be sent back to, which is most of them.
 *
 * <p>What made the old behaviour hard to see is that it was not several pieces of junk but one. Every
 * blank normalises to the same empty key, so a workspace ended up with a single node named nothing,
 * accumulating an edge from every unrelated conclusion whose entity list happened to contain a stray
 * empty string.
 */
class EntityPipelineTest extends MemoryTestBase {

    /**
     * A stored conclusion to hang edges on.
     *
     * <p>{@code entity_links.conclusion_id} is a foreign key, so a made-up id cannot stand in: the
     * insert fails on the constraint rather than on anything this test is about.
     */
    private String conclusion(PairKey pair, String content) {
        return writer.write(pair, "s1", List.of(new ConclusionWriter.Incoming(content, List.of(),
                ConclusionLevel.EXPLICIT, null, List.of(), List.of(), null)), Actor.DERIVER).outcomes().get(0)
                .conclusionId();
    }

    private long entityRows() {
        return jdbc.sql("SELECT count(*) FROM entities").query(Long.class).single();
    }

    private List<String> entityNames() {
        return jdbc.sql("SELECT name_norm FROM entities ORDER BY name_norm").query(String.class).list();
    }

    private long linkRows() {
        return jdbc.sql("SELECT count(*) FROM entity_links").query(Long.class).single();
    }

    @Test
    void aBlankNameNeverBecomesANode() {
        PairKey pair = seedPair("alice", "bob");
        seedSession("s1");

        entityPipeline.linkAll(pair, Map.of(conclusion(pair, "one"), List.of("   "), conclusion(pair, "two"),
                List.of(""), conclusion(pair, "three"), List.of("\t")));

        assertThat(entityRows()).isZero();
        assertThat(linkRows()).isZero();
    }

    /**
     * The blank is dropped and the rest of the list is kept.
     *
     * <p>The case that matters most, because it is what a stray trailing element in a real extraction
     * looks like: the conclusion still gets the entity it actually named.
     */
    @Test
    void aBlankIsDroppedWithoutTakingItsNeighboursWithIt() {
        PairKey pair = seedPair("alice", "bob");
        seedSession("s1");

        entityPipeline.linkAll(pair, Map.of(conclusion(pair, "one"), List.of("서울", "  ", "부산")));

        assertThat(entityNames()).containsExactly("부산", "서울");
        assertThat(linkRows()).isEqualTo(2);
    }

    /**
     * A null name is dropped rather than thrown on.
     *
     * <p>Belt and braces: {@code List.copyOf} in the model-output records rejects a null element before
     * it could reach here, so this is unreachable today through either the deriver or the dreamer. It
     * is asserted anyway because the filter is what makes that a design choice rather than a piece of
     * luck, and because {@code linkAll} is a public method on a service.
     */
    @Test
    void aNullNameIsDroppedRatherThanThrown() {
        PairKey pair = seedPair("alice", "bob");
        seedSession("s1");

        entityPipeline.linkAll(pair, Map.of(conclusion(pair, "one"), Arrays.asList("서울", null)));

        assertThat(entityNames()).containsExactly("서울");
    }

    /**
     * The path the deriver and the dreamer take, end to end.
     *
     * <p>{@code ConclusionWriter.write} is what both of them call, and it is where a blank name coming
     * out of a model used to turn into a node. Nothing is rejected here — the conclusion is stored, the
     * real entity is linked, and only the name that could not identify anything is gone.
     */
    @Test
    void aBlankNameFromModelOutputIsAbsorbedRatherThanRefused() {
        PairKey pair = seedPair("alice", "bob");
        seedSession("s1");

        var result = writer.write(pair, "s1", List.of(new ConclusionWriter.Incoming("alice was in 부산",
                Arrays.asList("  ", "부산"), ConclusionLevel.EXPLICIT, null, List.of(), List.of(), null)), Actor.DERIVER);

        assertThat(result.inserted()).isEqualTo(1);
        assertThat(entityNames()).containsExactly("부산");
    }

    /**
     * Names that differ only in case or surrounding space were already one node, and still are.
     *
     * <p>Pinned here because it was considered alongside the blank-name fix and deliberately left
     * alone: {@code normalizeName} already folds these, so there was nothing to repair. Asserting it
     * keeps the next reader from "fixing" it twice.
     */
    @Test
    void caseAndSurroundingSpaceStillFoldOntoOneNode() {
        PairKey pair = seedPair("alice", "bob");
        seedSession("s1");

        entityPipeline.linkAll(pair, Map.of(conclusion(pair, "one"), List.of("Seoul", "seoul", "  Seoul  ")));

        assertThat(entityNames()).containsExactly("seoul");
        assertThat(jdbc.sql("SELECT name_display FROM entities").query(String.class).single()).isEqualTo("Seoul");
    }

    /**
     * Nothing is left for provenance to find.
     *
     * <p>This is the reachability half of the damage, and it belongs on this path rather than at the
     * HTTP endpoint. {@code GET /recall/provenance?entity=} with whitespace used to answer 200 and hand
     * back the nameless node together with every conclusion linked to it. A test that posts a blank
     * name to the endpoint cannot pin that any more — the endpoint refuses it before a node could
     * exist, so the test would pass whether or not the node was ever possible. Going through
     * {@code ConclusionWriter.write} is what the deriver and the dreamer do, and it is the path where a
     * blank name can still arrive.
     */
    @Test
    void nothingIsLeftForProvenanceToFind() {
        PairKey pair = seedPair("alice", "bob");
        seedSession("s1");

        writer.write(pair, "s1", List.of(new ConclusionWriter.Incoming("alice was in 서울", List.of("  ", "서울"),
                ConclusionLevel.EXPLICIT, null, List.of(), List.of(), null)), Actor.DERIVER);

        assertThat(provenance.forEntity(pair, "   ", 10)).isEmpty();
        assertThat(provenance.forEntity(pair, "서울", 10)).isPresent();
    }

    /** A list that is entirely blank is the same as no entities at all: no embedding call, no rows. */
    @Test
    void aListOfNothingButBlanksProducesNoWork() {
        PairKey pair = seedPair("alice", "bob");
        seedSession("s1");

        entityPipeline.linkAll(pair, Map.of(conclusion(pair, "one"), List.of(" ", "\t", "")));

        assertThat(entityRows()).isZero();
    }
}
