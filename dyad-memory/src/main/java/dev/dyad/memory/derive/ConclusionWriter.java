package dev.dyad.memory.derive;

import dev.dyad.core.key.PairKey;
import dev.dyad.core.model.Actor;
import dev.dyad.core.model.ConclusionDraft;
import dev.dyad.core.model.ConclusionLevel;
import dev.dyad.core.model.DedupOutcome;
import dev.dyad.core.spi.Analyzer;
import dev.dyad.core.spi.EmbedPurpose;
import dev.dyad.core.spi.Embedder;
import dev.dyad.memory.entity.EntityPipeline;
import dev.dyad.memory.prompt.Prompts;
import dev.dyad.store.WorkspaceSettingsService;
import dev.dyad.store.repo.ConclusionRepository;
import dev.dyad.text.ContentHash;
import dev.dyad.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Everything between "the model said this" and "the row exists".
 *
 * <p>Normalise, analyse, hash, embed as one batch, upsert through dedup, then link entities to
 * whichever row actually survived. That last part matters: after a REPLACE the entities belong to the
 * new id, and after a REINFORCE they belong to the row that was already there. Linking to the id that
 * was proposed rather than the one dedup returned leaves dangling edges.
 */
@Service
public class ConclusionWriter {

    private final ConclusionRepository conclusions;
    private final EntityPipeline entityPipeline;
    private final Embedder embedder;
    private final WorkspaceSettingsService settings;

    public ConclusionWriter(
            ConclusionRepository conclusions,
            EntityPipeline entityPipeline,
            Embedder embedder,
            WorkspaceSettingsService settings) {
        this.conclusions = conclusions;
        this.entityPipeline = entityPipeline;
        this.embedder = embedder;
        this.settings = settings;
    }

    /** One conclusion on its way in, before the text derivations are computed. */
    public record Incoming(
            String content,
            List<String> entities,
            ConclusionLevel level,
            Double confidence,
            List<String> sourceIds,
            List<Long> messageIds,
            Instant expiresAt) {

        public static Incoming explicit(String content, List<String> entities, List<Long> messageIds) {
            return new Incoming(content, entities, ConclusionLevel.EXPLICIT, null, List.of(), messageIds, null);
        }
    }

    public record WriteResult(List<DedupOutcome> outcomes) {

        public long inserted() {
            return outcomes.stream().filter(o -> o.kind() == DedupOutcome.Kind.INSERTED).count();
        }

        public long reinforced() {
            return outcomes.stream().filter(o -> o.kind() == DedupOutcome.Kind.REINFORCED).count();
        }

        public long replaced() {
            return outcomes.stream().filter(o -> o.kind() == DedupOutcome.Kind.REPLACED).count();
        }
    }

    public WriteResult write(PairKey pair, String sessionName, List<Incoming> items, Actor actor) {
        return write(pair, sessionName, items, actor, Prompts.VERSION);
    }

    public WriteResult write(
            PairKey pair, String sessionName, List<Incoming> items, Actor actor, String promptVersion) {
        if (items.isEmpty()) {
            return new WriteResult(List.of());
        }
        Analyzer analyzer = settings.analyzerFor(pair.workspaceName());

        List<String> texts = items.stream().map(Incoming::content).toList();
        List<float[]> vectors = embedder.embedBatch(texts, EmbedPurpose.DOCUMENT);

        List<DedupOutcome> outcomes = new ArrayList<>(items.size());
        Map<String, List<String>> entityNames = new LinkedHashMap<>();
        List<String> replaced = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            Incoming item = items.get(i);
            String norm = Normalizer.normalize(item.content());
            ConclusionDraft draft =
                    ConclusionDraft.builder()
                            .pair(pair)
                            // Only explicit conclusions belong to a session; a deduction spans the pair's whole history.
                            .sessionName(item.level() == ConclusionLevel.EXPLICIT ? sessionName : null)
                            .content(item.content().strip())
                            .contentNorm(norm)
                            .contentAnalyzed(analyzer.analyze(item.content()))
                            .contentHash(ContentHash.of(norm))
                            .level(item.level())
                            .confidence(item.confidence())
                            .sourceIds(item.sourceIds())
                            .messageIds(item.messageIds())
                            .entityNames(item.entities())
                            .embedding(vectors.get(i))
                            .expiresAt(item.expiresAt())
                            .actor(actor)
                            .promptVersion(promptVersion)
                            .build();

            DedupOutcome outcome = conclusions.upsert(draft);
            outcomes.add(outcome);
            if (!item.entities().isEmpty()) {
                entityNames
                        .computeIfAbsent(outcome.conclusionId(), k -> new ArrayList<>())
                        .addAll(item.entities());
            }
            if (outcome.kind() == DedupOutcome.Kind.REPLACED && outcome.replacedId() != null) {
                replaced.add(outcome.replacedId());
            }
        }

        // Link before unlinking. The orphan sweep inside unlinkAll deletes any node left with no
        // references, so running it first destroys the node a replacement is about to re-attach —
        // then recreates it with a new id and a freshly paid-for embedding, for no change in state.
        entityPipeline.linkAll(pair, entityNames);
        entityPipeline.unlinkAll(pair.workspaceName(), replaced);
        return new WriteResult(List.copyOf(outcomes));
    }
}
