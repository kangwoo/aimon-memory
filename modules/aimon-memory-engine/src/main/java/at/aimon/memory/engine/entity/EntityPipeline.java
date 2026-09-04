package at.aimon.memory.engine.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.EntityRef;
import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.core.spi.Embedder;
import at.aimon.memory.store.repo.EntityRepository;

/**
 * Turns extracted entity names into nodes and pair-scoped edges.
 *
 * <p>Names are deduplicated across the whole batch before anything is embedded, so a batch mentioning
 * one place twenty times costs one embedding rather than twenty.
 */
@Service
public class EntityPipeline {

    private final EntityRepository entities;
    private final Embedder embedder;

    public EntityPipeline(EntityRepository entities, Embedder embedder) {
        this.entities = entities;
        this.embedder = embedder;
    }

    /**
     * @param namesByConclusion conclusion id to the entity names extracted alongside it
     */
    public void linkAll(PairKey pair, Map<String, List<String>> namesByConclusion) {
        Map<String, String> distinct = new LinkedHashMap<>();
        namesByConclusion.values().stream().flatMap(List::stream)
                .forEach(name -> distinct.putIfAbsent(EntityRepository.normalize(name), name));
        if (distinct.isEmpty()) {
            return;
        }

        List<String> displayNames = List.copyOf(distinct.values());
        List<float[]> vectors = embedder.embedBatch(displayNames, EmbedPurpose.ENTITY);

        Map<String, EntityRef> resolved = new LinkedHashMap<>();
        for (int i = 0; i < displayNames.size(); i++) {
            String display = displayNames.get(i);
            resolved.put(EntityRepository.normalize(display),
                    entities.upsert(pair.workspaceName(), display, null, vectors.get(i)));
        }

        namesByConclusion.forEach((conclusionId, names) -> {
            for (String name : names) {
                EntityRef entity = resolved.get(EntityRepository.normalize(name));
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
        List<float[]> vectors = embedder.embedBatch(names, EmbedPurpose.ENTITY);
        for (int i = 0; i < pending.size(); i++) {
            entities.updateEmbedding(pending.get(i).id(), vectors.get(i));
        }
        return pending.size();
    }
}
