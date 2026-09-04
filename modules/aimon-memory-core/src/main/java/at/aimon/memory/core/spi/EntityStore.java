package at.aimon.memory.core.spi;

import java.util.List;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.EntityMatch;

/**
 * The entity layer.
 *
 * <p>Nodes are workspace-scoped and edges carry the pair, which is the one structural improvement
 * over the source design: "서울" is embedded once for the whole workspace, while
 * {@code WHERE observer = ? AND observed = ?} on the edge keeps pair isolation intact.
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

    void link(String workspace, String entityId, String conclusionId, PairKey pair);
}
