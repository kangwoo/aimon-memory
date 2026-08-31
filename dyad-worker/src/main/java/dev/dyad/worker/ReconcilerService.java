package dev.dyad.worker;

import dev.dyad.core.model.Actor;
import dev.dyad.core.model.Conclusion;
import dev.dyad.core.model.EventType;
import dev.dyad.core.spi.EmbedPurpose;
import dev.dyad.core.spi.Embedder;
import dev.dyad.store.repo.ConclusionRepository;
import dev.dyad.memory.entity.EntityPipeline;
import dev.dyad.store.repo.QueueRepository;
import dev.dyad.store.repo.WorkspaceRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The janitor: embedding backfill, expiry, claim recovery, queue trimming.
 *
 * <p>Everything here is a self-healing pass over state that other components can leave inconsistent
 * when they fail halfway. A conclusion whose embedding call failed is invisible to semantic recall
 * with no error anywhere; the sweep is what makes that condition temporary rather than permanent.
 */
@Service
public class ReconcilerService {

    private static final Logger log = LoggerFactory.getLogger(ReconcilerService.class);
    private static final int BATCH = 100;

    private static final int WORKSPACES_PER_PASS = 100;

    private final ConclusionRepository conclusions;
    private final QueueRepository queue;
    private final WorkspaceRepository workspaces;
    private final EntityPipeline entities;
    private final Embedder embedder;
    private final Clock clock;

    public ReconcilerService(
            ConclusionRepository conclusions,
            QueueRepository queue,
            WorkspaceRepository workspaces,
            EntityPipeline entities,
            Embedder embedder,
            Clock clock) {
        this.conclusions = conclusions;
        this.queue = queue;
        this.workspaces = workspaces;
        this.entities = entities;
        this.embedder = embedder;
        this.clock = clock;
    }

    public record Report(
            int embedded, int entitiesEmbedded, int expired, int claimsReleased, int queueRowsDeleted) {

        int total() {
            return embedded + entitiesEmbedded + expired + claimsReleased + queueRowsDeleted;
        }
    }

    public Report run(Duration processedRetention) {
        int embedded = syncEmbeddings();
        int entityVectors = syncEntityEmbeddings();
        int expired = expire();
        int claims = queue.releaseExpiredClaims();
        int trimmed = queue.deleteProcessedBefore(clock.instant().minus(processedRetention));
        Report report = new Report(embedded, entityVectors, expired, claims, trimmed);
        if (report.total() > 0) {
            log.info("reconciler: {}", report);
        }
        return report;
    }

    /**
     * Backfill entity vectors.
     *
     * <p>An entity node with no vector is invisible to matching, so it contributes nothing to the
     * {@code ent} signal while still occupying a row. This is also the path a prompt-version change
     * runs through: clearing the vectors and letting the sweep rebuild them re-indexes the layer
     * without a migration.
     */
    public int syncEntityEmbeddings() {
        int total = 0;
        for (var workspace : workspaces.list(0, WORKSPACES_PER_PASS).items()) {
            try {
                total += entities.reindex(workspace.name(), BATCH);
            } catch (RuntimeException e) {
                log.warn("entity reindex failed for {}: {}", workspace.name(), e.getMessage());
            }
        }
        return total;
    }

    /** Backfill vectors for rows whose embedding call did not land. */
    public int syncEmbeddings() {
        List<Conclusion> pending = conclusions.pendingEmbedding(BATCH);
        if (pending.isEmpty()) {
            return 0;
        }
        List<String> texts = pending.stream().map(Conclusion::content).toList();
        try {
            List<float[]> vectors = embedder.embedBatch(texts, EmbedPurpose.DOCUMENT);
            for (int i = 0; i < pending.size(); i++) {
                conclusions.updateEmbedding(pending.get(i).id(), vectors.get(i));
            }
            return pending.size();
        } catch (RuntimeException e) {
            // Marked failed rather than retried forever: the next pass will pick it up, and the state
            // is visible in the audit log meanwhile instead of the row just being quietly unsearchable.
            pending.forEach(
                    c -> conclusions.markSyncFailed(c.pair().workspaceName(), c.id(), e.getMessage()));
            log.warn("embedding sync failed for {} conclusions: {}", pending.size(), e.getMessage());
            return 0;
        }
    }

    /**
     * Soft-delete what has reached its TTL.
     *
     * <p>Soft, and logged as EXPIRE rather than DELETE. Expiry is the system's own decision, and the
     * event type is what tells a later reader that nobody asked for this row to go.
     */
    public int expire() {
        List<Conclusion> due = conclusions.expired(clock.instant(), BATCH);
        Map<String, List<String>> byWorkspace = new LinkedHashMap<>();
        for (Conclusion conclusion : due) {
            String workspace = conclusion.pair().workspaceName();
            conclusions.softDelete(
                    workspace,
                    conclusion.id(),
                    Actor.RECONCILER,
                    Map.of("expires_at", String.valueOf(conclusion.expiresAt())),
                    EventType.EXPIRE);
            byWorkspace.computeIfAbsent(workspace, k -> new ArrayList<>()).add(conclusion.id());
        }
        // Expiry has to clean up after itself exactly as an explicit delete does. Leaving the edges
        // behind kept an expired fact contributing to entity link counts, and kept its node alive
        // through the orphan sweep — an entity that outlives everything it was ever attached to.
        byWorkspace.forEach(entities::unlinkAll);
        return due.size();
    }
}
