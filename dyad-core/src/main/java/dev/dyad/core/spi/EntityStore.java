package dev.dyad.core.spi;

import dev.dyad.core.key.PairKey;
import dev.dyad.core.model.EntityMatch;
import java.util.List;

/**
 * The entity layer.
 *
 * <p>Nodes are workspace-scoped and edges carry the pair, which is the one structural improvement
 * over the source design: "서울" is embedded once for the whole workspace, while
 * {@code WHERE observer = ? AND observed = ?} on the edge keeps pair isolation intact.
 */
public interface EntityStore {

    /** Nearest entity nodes in the workspace, with their link counts already attached. */
    List<EntityMatch> match(String workspace, float[] q, int topK);

    void link(String workspace, String entityId, String conclusionId, PairKey pair);
}
