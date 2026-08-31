package dev.dyad.store;

import dev.dyad.core.config.BatchSettings;
import dev.dyad.core.config.DedupSettings;
import dev.dyad.core.config.RecallSettings;
import dev.dyad.core.model.FusionWeights;
import dev.dyad.core.spi.Analyzer;
import dev.dyad.store.repo.WorkspaceRepository;
import dev.dyad.text.AnalyzerRegistry;
import dev.dyad.text.AnalyzerResolver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Reads workspace configuration and hands out settings and analyzers.
 *
 * <p>Cached, because recall reads this on every request and a workspace's configuration changes
 * roughly never. {@link #invalidate} is called from the update path rather than relying on a TTL —
 * a stale half-life that silently persists for ten minutes is the kind of thing that makes a tuning
 * session produce nonsense.
 */
@Service
public class WorkspaceSettingsService implements AnalyzerResolver {

    private static final int MAX_CACHED_WORKSPACES = 10_000;

    private final WorkspaceRepository workspaces;
    private final AnalyzerRegistry analyzers;
    private final Map<String, WorkspaceSettings> cache = new ConcurrentHashMap<>();

    public WorkspaceSettingsService(WorkspaceRepository workspaces, AnalyzerRegistry analyzers) {
        this.workspaces = workspaces;
        this.analyzers = analyzers;
    }

    public WorkspaceSettings forWorkspace(String workspaceName) {
        // Dropped wholesale rather than evicted by age. This is a cache in front of a row that
        // changes roughly never, and an unbounded map keyed by a caller-supplied name is a slow leak
        // in any deployment with a workspace per tenant.
        if (cache.size() > MAX_CACHED_WORKSPACES) {
            cache.clear();
        }
        return cache.computeIfAbsent(workspaceName, this::load);
    }

    @Override
    public Analyzer analyzerFor(String workspaceName) {
        return analyzers.forLanguage(forWorkspace(workspaceName).languageTag());
    }

    public void invalidate(String workspaceName) {
        cache.remove(workspaceName);
    }

    private WorkspaceSettings load(String workspaceName) {
        Map<String, Object> config =
                workspaces.find(workspaceName).map(w -> w.configuration()).orElse(Map.of());
        if (config.isEmpty()) {
            return WorkspaceSettings.DEFAULT;
        }
        RecallSettings recall =
                new RecallSettings(
                        weights(config),
                        number(config, "recall.half_life_days", RecallSettings.DEFAULT.halfLifeDays()),
                        number(config, "recall.threshold", RecallSettings.DEFAULT.threshold()),
                        (int) number(config, "recall.oversample", RecallSettings.DEFAULT.oversample()),
                        (int) number(config, "recall.entity_top_k", RecallSettings.DEFAULT.entityTopK()),
                        number(config, "recall.entity_sim_cut", RecallSettings.DEFAULT.entitySimCut()));
        DedupSettings dedup =
                new DedupSettings(
                        number(config, "dedup.cosine_distance_max", DedupSettings.DEFAULT.cosineDistanceMax()),
                        (int) number(config, "dedup.unique_token_weight", DedupSettings.DEFAULT.uniqueTokenWeight()));
        BatchSettings batch =
                new BatchSettings(
                        (int) number(config, "batch.token_threshold", BatchSettings.DEFAULT.tokenThreshold()),
                        Duration.ofMinutes((long) number(config, "batch.max_age_minutes", 30)),
                        Duration.ofSeconds((long) number(config, "batch.idle_flush_seconds", 3)));
        return new WorkspaceSettings(
                config.getOrDefault("language", "und").toString(),
                recall,
                dedup,
                batch,
                flag(config, "observe_me", true),
                flag(config, "observe_others", true));
    }

    private static FusionWeights weights(Map<String, Object> config) {
        Object raw = config.get("recall.weights");
        if (!(raw instanceof List<?> list) || list.size() != 6) {
            return FusionWeights.DEFAULT;
        }
        double[] values = new double[6];
        for (int i = 0; i < 6; i++) {
            if (!(list.get(i) instanceof Number n)) {
                return FusionWeights.DEFAULT;
            }
            values[i] = n.doubleValue();
        }
        // The constructor rejects anything that does not sum to 1.00; a bad override falls back rather
        // than taking the whole workspace down on the next recall.
        try {
            return new FusionWeights(values[0], values[1], values[2], values[3], values[4], values[5]);
        } catch (RuntimeException e) {
            return FusionWeights.DEFAULT;
        }
    }

    private static double number(Map<String, Object> config, String key, double fallback) {
        Object value = config.get(key);
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private static boolean flag(Map<String, Object> config, String key, boolean fallback) {
        Object value = config.get(key);
        return value instanceof Boolean b ? b : fallback;
    }
}
