package at.aimon.memory.worker;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.key.WorkUnitKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.Conclusion;
import at.aimon.memory.core.model.EventType;
import at.aimon.memory.core.spi.ConclusionStore;
import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.core.spi.Embedder;
import at.aimon.memory.engine.entity.EntityPipeline;
import at.aimon.memory.store.repo.DreamRepository;
import at.aimon.memory.store.repo.QueueRepository;
import at.aimon.memory.store.repo.WorkspaceRepository;

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

    /**
     * How long a scheduled dream may sit pending before the sweep assumes its work unit was lost.
     *
     * <p>Long enough that a dream queued normally has been claimed and started well before this, so
     * the sweep never races the worker that is already on it.
     */
    private static final Duration DREAM_ORPHAN_GRACE = Duration.ofMinutes(15);

    /**
     * How many times the sweep will put one dream back on the queue before giving up on it.
     *
     * <p>Re-enqueueing is right for a unit that was genuinely lost between the two statements that
     * schedule and queue it, and that is a one-off. Nothing in a pending row distinguishes it from a
     * dream whose work will never close it — a consumer that forgets to, a batch quarantined after
     * max-attempts — and for that one the sweep is an unbounded loop paying for a model call every
     * pass. Three attempts is generous for a lost enqueue and cheap for a stuck one.
     */
    private static final int MAX_DREAM_REQUEUES = 3;

    private final ConclusionStore conclusions;
    private final QueueRepository queue;
    private final WorkspaceRepository workspaces;
    private final EntityPipeline entities;
    private final DreamRepository dreams;
    private final Embedder embedder;
    private final Clock clock;

    public ReconcilerService(ConclusionStore conclusions, QueueRepository queue, WorkspaceRepository workspaces,
            EntityPipeline entities, DreamRepository dreams, Embedder embedder, Clock clock) {
        this.conclusions = conclusions;
        this.queue = queue;
        this.workspaces = workspaces;
        this.entities = entities;
        this.dreams = dreams;
        this.embedder = embedder;
        this.clock = clock;
    }

    public record Report(int embedded, int entitiesEmbedded, int expired, int claimsReleased, int queueRowsDeleted,
            int dreamsRequeued, int dreamsAbandoned) {

        int total() {
            return embedded + entitiesEmbedded + expired + claimsReleased + queueRowsDeleted + dreamsRequeued
                    + dreamsAbandoned;
        }
    }

    public Report run(Duration processedRetention) {
        int embedded = syncEmbeddings();
        int entityVectors = syncEntityEmbeddings();
        int expired = expire();
        int claims = queue.releaseExpiredClaims();
        int trimmed = queue.deleteProcessedBefore(clock.instant().minus(processedRetention));
        DreamSweep dreamSweep = sweepOrphanedDreams();
        Report report = new Report(embedded, entityVectors, expired, claims, trimmed, dreamSweep.requeued(),
                dreamSweep.abandoned());
        if (report.total() > 0) {
            log.info("reconciler: {}", report);
        }
        return report;
    }

    /**
     * Re-enqueue dreams that were scheduled but whose work unit never reached the queue.
     *
     * <p>Scheduling and enqueueing are two statements, and anything that happens between them — a
     * process death, or the scheduler that used to write the row and enqueue nothing at all — leaves
     * a pending dream nothing will ever pick up. That is not merely lost work: the partial unique
     * index treats a pending row as in flight, so the pair can never dream again and the manual
     * endpoint answers 409 forever. This is the only thing that clears it.
     *
     * <p>The queue is checked before enqueueing so a dream already waiting is not queued twice.
     *
     * <p><b>Bounded.</b> Re-enqueueing assumes the unit was lost on the way to the queue, and for a
     * dream nothing will ever close — a consumer that leaves the row pending, a batch quarantined
     * after max-attempts — that assumption never stops being true: the sweep finds no pending item,
     * concludes the unit is lost again, and pays for another model call, every pass, forever. After
     * {@link #MAX_DREAM_REQUEUES} the dream is failed instead, which also frees the partial unique
     * index so the pair is not locked out of dreaming for good.
     */
    public DreamSweep sweepOrphanedDreams() {
        int requeued = 0;
        int abandoned = 0;
        for (DreamRepository.Dream dream : dreams.pending(BATCH)) {
            if (clock.instant().isBefore(dream.createdAt().plus(DREAM_ORPHAN_GRACE))) {
                continue;
            }
            WorkUnitKey key = workUnitFor(dream);
            if (!queue.pending(key.encode(), 1).isEmpty()) {
                continue;
            }
            if (dream.requeueCount() >= MAX_DREAM_REQUEUES) {
                dreams.fail(dream.id(), "re-enqueued " + dream.requeueCount() + " times without the work unit ever"
                        + " completing; giving up so the pair can dream again");
                abandoned++;
                continue;
            }
            queue.enqueue(key, Map.of("dream_id", dream.id()), 0);
            dreams.recordRequeue(dream.id());
            requeued++;
        }
        if (requeued > 0) {
            log.warn("re-enqueued {} dreams that were scheduled but never queued", requeued);
        }
        if (abandoned > 0) {
            log.error("failed {} dreams that were re-enqueued {} times and never ran", abandoned, MAX_DREAM_REQUEUES);
        }
        return new DreamSweep(requeued, abandoned);
    }

    /** @param abandoned dreams failed because re-enqueueing them was not getting them run */
    public record DreamSweep(int requeued, int abandoned) {
    }

    private static WorkUnitKey workUnitFor(DreamRepository.Dream dream) {
        PairKey pair = new PairKey(dream.workspaceName(), dream.observer(), dream.observed());
        return DreamRepository.DreamType.CARD_REFRESH.wire().equals(dream.dreamType())
                ? WorkUnitKey.cardRefresh(pair)
                : WorkUnitKey.dream(pair);
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
            // Inside the try, so a misaligned batch marks the rows sync-failed and is visible in the
            // audit log, exactly as a provider failure is — rather than throwing out of the loop
            // partway and leaving some rows updated and the rest silently unsearchable again.
            List<float[]> vectors = Embedder.requireAligned(texts, embedder.embedBatch(texts, EmbedPurpose.DOCUMENT));
            for (int i = 0; i < pending.size(); i++) {
                conclusions.updateEmbedding(pending.get(i).id(), vectors.get(i));
            }
            return pending.size();
        } catch (RuntimeException e) {
            // Marked failed rather than retried forever: the next pass will pick it up, and the state
            // is visible in the audit log meanwhile instead of the row just being quietly unsearchable.
            pending.forEach(c -> conclusions.markSyncFailed(c.pair().workspaceName(), c.id(), e.getMessage()));
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
            conclusions.softDelete(workspace, conclusion.id(), Actor.RECONCILER,
                    Map.of("expires_at", String.valueOf(conclusion.expiresAt())), EventType.EXPIRE);
            byWorkspace.computeIfAbsent(workspace, k -> new ArrayList<>()).add(conclusion.id());
        }
        // Expiry has to clean up after itself exactly as an explicit delete does. Leaving the edges
        // behind kept an expired fact contributing to entity link counts, and kept its node alive
        // through the orphan sweep — an entity that outlives everything it was ever attached to.
        byWorkspace.forEach(entities::unlinkAll);
        return due.size();
    }
}
