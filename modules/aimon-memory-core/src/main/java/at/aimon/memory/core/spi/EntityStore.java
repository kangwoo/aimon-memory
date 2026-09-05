package at.aimon.memory.core.spi;

import java.util.List;
import java.util.Optional;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.EntityLink;
import at.aimon.memory.core.model.EntityMatch;
import at.aimon.memory.core.model.EntityRef;

/**
 * The entity layer.
 *
 * <p>Nodes are workspace-scoped and edges carry the pair, which is the one structural improvement
 * over the source design: "서울" is embedded once for the whole workspace, while
 * {@code WHERE observer = ? AND observed = ?} on the edge keeps pair isolation intact.
 *
 * <p>Widened from two methods to the surface callers outside {@code aimon-memory-store} actually
 * use, for the reason spelled out on {@link ConclusionStore}. {@code entitiesFor} and
 * {@code entityIdsFor} stay on the concrete class because only that module's tests reach them, and
 * {@code findById} because nothing does. The static {@code normalize} and {@code MERGE_SIMILARITY}
 * stay there too: they are this backend's matching policy, not a requirement on the next one's.
 */
public interface EntityStore {

    /**
     * Nearest entity nodes in the workspace, with their link counts already attached.
     *
     * <p>The node search is workspace-wide — that is the point of workspace-scoped nodes — but the
     * count that comes back is the count <em>within the pair</em>. It feeds {@code countWeight}, whose
     * whole claim is that an entity linked to everything in a pair discriminates nothing inside it;
     * counting workspace-wide instead answered a different question and got quieter as more unrelated
     * tenants were added.
     */
    List<EntityMatch> match(PairKey pair, float[] q, int topK);

    /** Resolve or create a node, merging into a near-identical neighbour where one exists. */
    EntityRef upsert(String workspace, String displayName, String kind, float[] embedding);

    /**
     * The key this store would file a display name under — "would you treat these two as the same
     * name?", asked without a round trip.
     *
     * <p>An instance method rather than a static, and on the interface rather than off it, because
     * both readings of the alternative are wrong. {@code EntityPipeline} collapses the names one
     * extraction produced before it pays to embed them, and it has to collapse them the same way the
     * store will or it embeds two spellings of one entity and links only one; so the question is
     * genuinely part of this contract and cannot stay a static helper on one implementation, which is
     * what it was. Making it {@code static} on the interface instead would fix the rule for every
     * backend, and it is exactly the kind of thing that differs — collation and case folding are not
     * universal.
     */
    String normalizeName(String displayName);

    /**
     * Look a node up by the name a human typed.
     *
     * <p>Takes the display name rather than the normalised key, which is a deliberate narrowing of
     * what used to cross this boundary: the one caller outside the store module reached for
     * {@code EntityRepository.normalize(...)} — a static on the implementation — and passed the
     * result to {@code findByNorm}. That made this backend's normalisation rule part of the calling
     * convention, so a second implementation would have had to reproduce it exactly or silently
     * return nothing. Writes never had the problem: {@code upsert} normalises what it is given.
     * Reads now match.
     *
     * <p>{@code findByNorm} still exists on the concrete class, where its remaining callers — that
     * module's own tests, which are asserting about the {@code name_norm} column — belong.
     */
    Optional<EntityRef> findByName(String workspace, String displayName);

    void link(String workspace, String entityId, String conclusionId, PairKey pair);

    List<String> linkedConclusionIds(String workspace, String entityId, PairKey pair);

    /** The edges among a known set of nodes and conclusions — the entity signal's explain input. */
    List<EntityLink> linksAmong(String workspace, PairKey pair, List<String> entityIds, List<String> conclusionIds);

    void unlinkConclusion(String workspace, String conclusionId);

    /** Drops nodes no edge points at any more. Returns how many went. */
    int deleteOrphans(String workspace);

    /** Nodes written before an embedder was reachable; the reconciler's backfill queue. */
    List<EntityRef> withoutEmbedding(String workspace, int limit);

    void updateEmbedding(String entityId, float[] embedding);
}
