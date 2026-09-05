package at.aimon.memory.engine.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.EntityRef;
import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.core.spi.Embedder;
import at.aimon.memory.core.spi.EntityStore;

/**
 * Turns extracted entity names into nodes and pair-scoped edges.
 *
 * <p>Names are deduplicated across the whole batch before anything is embedded, so a batch mentioning
 * one place twenty times costs one embedding rather than twenty.
 */
@Service
public class EntityPipeline {

    private static final Logger log = LoggerFactory.getLogger(EntityPipeline.class);

    private final EntityStore entities;
    private final Embedder embedder;

    public EntityPipeline(EntityStore entities, Embedder embedder) {
        this.entities = entities;
        this.embedder = embedder;
    }

    /**
     * A name that can identify something. Blank ones are dropped before anything is embedded.
     *
     * <p>Every entity name in the system arrives through {@link #linkAll} — the injection endpoint, the
     * deriver and the dreamer all funnel here — and until this filter existed a blank one became a real
     * node. {@code normalizeName} maps {@code ""}, {@code "   "} and {@code "\t"} onto the same empty
     * key, so they did not even become several pieces of junk: they became <em>one</em> node named
     * nothing, which every conclusion carrying a stray empty string then linked to.
     *
     * <p>That node is worse than the orphans {@link #unlinkAll} sweeps up. It has edges, so the orphan
     * sweep keeps it; it has a vector, so it sits in the index and takes a slot in {@code entityTopK};
     * and because its edges are to conclusions that have nothing in common, a query landing near its
     * vector hands the {@code ent} signal to all of them at once. That shape is the damage, and it
     * holds whatever embedder is configured.
     *
     * <p>The <em>size</em> of it was only ever measured on the stand-in embedder, and that number says
     * less than it looks like it says. Three unrelated facts came back at {@code ent = 0.996} each —
     * which is {@code countWeight} for three links and nothing more — with the explain payload
     * reporting {@code matchedEntities: [""]}. It took a particular query to see it: {@code
     * StubEmbedder} falls back to the token {@code "empty"} for text it can find no tokens in, so the
     * blank node carries that word's vector and the figure needs a query carrying the same one. A query
     * for anything else scored {@code ent = 0.0}. Where a real embedder puts {@code embed("")} relative
     * to real queries has not been measured, so how often the node is reached is unknown; what is known
     * is that when it is reached, everything hanging off it is lifted together.
     *
     * <p>Filtered rather than refused, because most of what reaches here is model output and a work unit
     * cannot be handed back to its author. Throwing would not even discard the batch — {@code
     * ConclusionWriter.write} is not transactional and every upsert has already committed, so the
     * conclusions would stay, stripped of the entity edges this method exists to write, and the work
     * unit would be retried five times. Each retry re-derives and re-writes the same facts, which dedup
     * counts as reinforcement and adds to {@code times_derived}, inflating the {@code reinf} signal, and
     * the batch is quarantined at the end of it. {@code DeriverService} and {@code DreamerService}
     * already skip an item whose <em>content</em> is blank for that reason; this is the same judgement
     * one field over. An HTTP caller gets the stricter answer instead, from {@code
     * Requests.CreateConclusion}, because a client can fix its own bug.
     */
    private static boolean isUsable(String name) {
        return name != null && !name.isBlank();
    }

    /**
     * @param namesByConclusion conclusion id to the entity names extracted alongside it
     */
    public void linkAll(PairKey pair, Map<String, List<String>> namesByConclusion) {
        Map<String, String> distinct = new LinkedHashMap<>();
        int dropped = 0;
        for (List<String> names : namesByConclusion.values()) {
            for (String name : names) {
                if (isUsable(name)) {
                    distinct.putIfAbsent(entities.normalizeName(name), name);
                } else {
                    dropped++;
                }
            }
        }
        // Said out loud, because the rest of this class's reasoning is that an HTTP caller is told and
        // a model is not. Not telling anyone at all would leave an extractor that has started emitting
        // blank names looking exactly like one that has not, and this is the layer that sees most of
        // them. Debug rather than warn: one stray element is ordinary, and the number is the signal.
        if (dropped > 0) {
            log.debug("dropped {} blank entity name(s) for {}, {} usable name(s) left", dropped, pair, distinct.size());
        }
        if (distinct.isEmpty()) {
            return;
        }

        List<String> displayNames = List.copyOf(distinct.values());
        List<float[]> vectors = Embedder.requireAligned(displayNames,
                embedder.embedBatch(displayNames, EmbedPurpose.ENTITY));

        Map<String, EntityRef> resolved = new LinkedHashMap<>();
        for (int i = 0; i < displayNames.size(); i++) {
            String display = displayNames.get(i);
            resolved.put(entities.normalizeName(display),
                    entities.upsert(pair.workspaceName(), display, null, vectors.get(i)));
        }

        namesByConclusion.forEach((conclusionId, names) -> {
            for (String name : names) {
                EntityRef entity = resolved.get(entities.normalizeName(name));
                if (entity != null) {
                    entities.link(pair.workspaceName(), entity.id(), conclusionId, pair);
                }
            }
        });
    }

    /**
     * Detach a conclusion and clean up anything it was the last reference to.
     *
     * <p>Called from every delete path. Orphan nodes are not merely untidy: they still count toward
     * nothing, but they occupy the vector index and can be returned as a top-k match for a query,
     * contributing a boost to no one.
     */
    public void unlink(String workspace, String conclusionId) {
        unlinkAll(workspace, List.of(conclusionId));
    }

    /**
     * Detach several conclusions, then sweep once.
     *
     * <p>The orphan sweep is an anti-join over the workspace's entities. Running it per conclusion
     * meant a batch with twenty replacements paid for twenty scans to reach the same end state.
     */
    public void unlinkAll(String workspace, List<String> conclusionIds) {
        if (conclusionIds.isEmpty()) {
            return;
        }
        conclusionIds.forEach(id -> entities.unlinkConclusion(workspace, id));
        entities.deleteOrphans(workspace);
    }

    /** Backfill vectors for nodes created before an embedder was configured, or after a re-index. */
    public int reindex(String workspace, int batchSize) {
        List<EntityRef> pending = entities.withoutEmbedding(workspace, batchSize);
        if (pending.isEmpty()) {
            return 0;
        }
        List<String> names = new ArrayList<>(pending.size());
        pending.forEach(entity -> names.add(entity.nameDisplay()));
        List<float[]> vectors = Embedder.requireAligned(names, embedder.embedBatch(names, EmbedPurpose.ENTITY));
        for (int i = 0; i < pending.size(); i++) {
            entities.updateEmbedding(pending.get(i).id(), vectors.get(i));
        }
        return pending.size();
    }
}
