package at.aimon.memory.recall;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Conclusion;
import at.aimon.memory.core.model.EntityRef;
import at.aimon.memory.core.model.Message;
import at.aimon.memory.store.repo.ConclusionRepository;
import at.aimon.memory.store.repo.EntityRepository;
import at.aimon.memory.store.repo.MessageRepository;

/**
 * Entity-anchored provenance: from a name back to the sentences that produced the belief.
 *
 * <p>Neither source design can do this. One has the entity index but no reasoning tree, so it can
 * find the conclusions and stops there; the other has the tree but nothing to anchor a lookup on, so
 * you must already know which conclusion to ask about. Chaining them answers "why does the system
 * think this" with zero model calls, which also makes the answer the same every time it is asked.
 *
 * <pre>
 *   entity "서울"
 *     └ entity_links (observer, observed)
 *         └ conclusions
 *             └ source_ids  → premise conclusions
 *                 └ message_ids → the original messages
 * </pre>
 */
@Service
public class ProvenanceService {

    /** Depth cap on the premise walk. A cycle in source_ids should not become an infinite response. */
    private static final int MAX_DEPTH = 4;

    private final EntityRepository entities;
    private final ConclusionRepository conclusions;
    private final MessageRepository messages;

    public ProvenanceService(EntityRepository entities, ConclusionRepository conclusions, MessageRepository messages) {
        this.entities = entities;
        this.conclusions = conclusions;
        this.messages = messages;
    }

    /**
     * @param unresolvedPremiseIds premises named in {@code source_ids} that no longer resolve, because
     *     they were deleted. Reported rather than dropped: a chain that is quietly shorter than the
     *     conclusion claims reads as though the reasoning had fewer steps, when in fact part of the
     *     evidence is gone — which is exactly what someone auditing an autonomous edit needs to know.
     */
    public record ConclusionProvenance(Conclusion conclusion, List<Conclusion> premises, List<Message> sourceMessages,
            List<String> unresolvedPremiseIds) {
    }

    public record EntityProvenance(EntityRef entity, List<ConclusionProvenance> conclusions) {
    }

    /** @return empty when the workspace has no entity by that name */
    public Optional<EntityProvenance> forEntity(PairKey pair, String entityName, int limit) {
        String workspace = pair.workspaceName();
        Optional<EntityRef> entity = entities.findByNorm(workspace, EntityRepository.normalize(entityName));
        if (entity.isEmpty()) {
            return Optional.empty();
        }
        List<String> conclusionIds = entities.linkedConclusionIds(workspace, entity.get().id(), pair);
        List<Conclusion> anchored = conclusions.byIds(workspace, conclusionIds);
        List<ConclusionProvenance> out = new ArrayList<>();
        for (Conclusion conclusion : anchored.stream().limit(limit).toList()) {
            out.add(forConclusion(pair, conclusion));
        }
        return Optional.of(new EntityProvenance(entity.get(), out));
    }

    public ConclusionProvenance forConclusion(PairKey pair, Conclusion conclusion) {
        String workspace = pair.workspaceName();
        List<Conclusion> premises = walkPremises(workspace, conclusion);

        // Message ids come from the conclusion and from everything it rests on: a deduction's evidence
        // is the text behind its premises, not behind itself, since a deduction was never said out loud.
        Set<Long> messageIds = new LinkedHashSet<>(conclusion.messageIds());
        premises.forEach(premise -> messageIds.addAll(premise.messageIds()));
        List<Message> sources = messages.byIds(workspace, List.copyOf(messageIds));

        Set<String> resolved = new LinkedHashSet<>();
        premises.forEach(premise -> resolved.add(premise.id()));
        List<String> missing = conclusion.sourceIds().stream().filter(id -> !resolved.contains(id)).toList();

        return new ConclusionProvenance(conclusion, premises, sources, missing);
    }

    private List<Conclusion> walkPremises(String workspace, Conclusion root) {
        List<Conclusion> collected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(root.id());
        List<String> frontier = new ArrayList<>(root.sourceIds());
        for (int depth = 0; depth < MAX_DEPTH && !frontier.isEmpty(); depth++) {
            List<String> unseen = frontier.stream().filter(seen::add).toList();
            if (unseen.isEmpty()) {
                break;
            }
            List<Conclusion> level = conclusions.byIds(workspace, unseen);
            collected.addAll(level);
            frontier = level.stream().flatMap(c -> c.sourceIds().stream()).toList();
        }
        return List.copyOf(collected);
    }

    /** The other direction: what has been concluded on top of this. */
    public List<Conclusion> dependents(String workspace, String conclusionId) {
        return conclusions.derivedFrom(workspace, conclusionId);
    }
}
