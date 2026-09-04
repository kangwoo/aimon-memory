package at.aimon.memory.store;

import at.aimon.memory.core.config.BatchSettings;
import at.aimon.memory.core.config.DedupSettings;
import at.aimon.memory.core.config.RecallSettings;

/**
 * Everything a workspace can tune, resolved from its {@code configuration} JSON.
 *
 * <p>The ranking weights and the decay half-life are configuration rather than constants on purpose.
 * The initial values are inherited from another system's tuning against a different corpus, and the
 * honest position is that they are a starting point — a re-tune against real traffic must not
 * require a deployment.
 */
public record WorkspaceSettings(String languageTag, RecallSettings recall, DedupSettings dedup, BatchSettings batch,
        boolean observeMe, boolean observeOthers) {

    public static final WorkspaceSettings DEFAULT = new WorkspaceSettings("und", RecallSettings.DEFAULT,
            DedupSettings.DEFAULT, BatchSettings.DEFAULT, true, true);
}
