package at.aimon.memory.store;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import at.aimon.memory.core.config.BatchSettings;
import at.aimon.memory.core.config.ConfigurationException;
import at.aimon.memory.core.config.DedupSettings;
import at.aimon.memory.core.config.RecallSettings;
import at.aimon.memory.core.model.FusionWeights;
import at.aimon.memory.core.spi.Analyzer;
import at.aimon.memory.store.repo.WorkspaceRepository;
import at.aimon.memory.text.AnalyzerRegistry;
import at.aimon.memory.text.AnalyzerResolver;

/**
 * Reads workspace configuration and hands out settings and analyzers.
 *
 * <p>Cached, because recall reads this on every request and a workspace's configuration changes
 * roughly never. {@link #invalidate} is called from the update path rather than relying on a TTL —
 * a stale half-life that silently persists for ten minutes is the kind of thing that makes a tuning
 * session produce nonsense.
 *
 * <p><b>Validated when written, tolerant when read.</b> {@link #validate} runs at the write boundary
 * — inside {@code WorkspaceRepository}, so it covers every route that reaches the column rather than
 * whichever one was noticed — and rejects an unknown key or an out-of-range value with a 422. {@link
 * #load} keeps falling back to defaults, because a row that predates a validation rule must not take
 * the workspace's recall down on the next request — but it now says so in the log, where before it
 * said nothing at all.
 */
@Service
public class WorkspaceSettingsService implements AnalyzerResolver {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSettingsService.class);

    private static final int MAX_CACHED_WORKSPACES = 10_000;

    /**
     * Every key a workspace may set.
     *
     * <p>An allowlist, so a misspelling is an error rather than a setting that appears to have been
     * accepted. {@code recall.half_life} instead of {@code recall.half_life_days} used to store
     * cleanly, read back in the response body, and change nothing.
     */
    private static final Set<String> KNOWN_KEYS = Set.of("language", "recall.weights", "recall.half_life_days",
            "recall.threshold", "recall.oversample", "recall.entity_top_k", "recall.entity_sim_cut",
            "dedup.cosine_distance_max", "dedup.unique_token_weight", "batch.token_threshold", "batch.max_age_minutes",
            "batch.idle_flush_seconds", "observe_me", "observe_others");

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
        // computeIfAbsent, and `load` therefore runs a JDBC query while holding a bin lock on the
        // map. That is the right trade as written — concurrent misses on one workspace collapse to a
        // single query instead of a stampede, and the query is one indexed read — but it puts a
        // standing condition on `load`: it must never reach back into this cache, directly or through
        // anything it calls. A recursive computeIfAbsent on the same map does not degrade, it
        // deadlocks the calling thread outright, and the caller here is an HTTP request thread on
        // every recall. Anything `load` needs that is not the workspaces table belongs above this
        // line, resolved before the lookup.
        return cache.computeIfAbsent(workspaceName, this::load);
    }

    @Override
    public Analyzer analyzerFor(String workspaceName) {
        return analyzers.forLanguage(forWorkspace(workspaceName).languageTag());
    }

    public void invalidate(String workspaceName) {
        cache.remove(workspaceName);
    }

    /**
     * Reject tuning keys written at a level nothing reads them from.
     *
     * <p>Peers and sessions have a {@code configuration} column too, and {@link #load} reads only the
     * workspace's. So {@code PUT /v1/workspaces/ws/peers/alice/configuration} with {@code
     * {"recall.half_life_days": 5}} stored cleanly, came back in the response body, and changed
     * nothing — the same silent drop the workspace routes were fixed for, one level down. Storing an
     * unrecognised key here is still allowed: it is opaque client data, and nothing about it claims to
     * tune anything.
     *
     * @throws ConfigurationException naming the key and where it belongs, for a 422
     */
    public static void rejectTuningKeys(String level, Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return;
        }
        for (String key : config.keySet()) {
            if (KNOWN_KEYS.contains(key)) {
                throw new ConfigurationException("'" + key + "' is workspace configuration; set on a " + level
                        + " nothing reads it. Put it on the workspace instead.");
            }
        }
    }

    /**
     * Check a configuration before it is stored.
     *
     * @throws ConfigurationException naming the offending key, for a 422
     */
    public static void validate(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return;
        }
        for (String key : config.keySet()) {
            if (!KNOWN_KEYS.contains(key)) {
                throw new ConfigurationException("unknown configuration key '" + key + "'; allowed: "
                        + String.join(", ", KNOWN_KEYS.stream().sorted().toList()));
            }
        }
        parse(config, true);
    }

    private WorkspaceSettings load(String workspaceName) {
        Map<String, Object> config = workspaces.find(workspaceName).map(w -> w.configuration()).orElse(Map.of());
        if (config.isEmpty()) {
            return WorkspaceSettings.DEFAULT;
        }
        try {
            return parse(config, true);
        } catch (ConfigurationException e) {
            // Stored before the rule existed, or written straight into the table. Falling back keeps
            // the workspace answering; the log is what makes the fallback discoverable, since the
            // symptom otherwise is only that tuning had no effect.
            log.warn("workspace {} has an invalid configuration and is falling back to defaults: {}", workspaceName,
                    e.getMessage());
            return parse(config, false);
        }
    }

    /**
     * @param strict throw on a value that cannot be honoured, instead of substituting the default
     */
    private static WorkspaceSettings parse(Map<String, Object> config, boolean strict) {
        RecallSettings recall = new RecallSettings(weights(config, strict),
                bounded(config, "recall.half_life_days", RecallSettings.DEFAULT.halfLifeDays(), 0.0, false, 100_000.0,
                        strict),
                bounded(config, "recall.threshold", RecallSettings.DEFAULT.threshold(), 0.0, true, 1.0, strict),
                (int) bounded(config, "recall.oversample", RecallSettings.DEFAULT.oversample(), 1.0, true, 100.0,
                        strict),
                (int) bounded(config, "recall.entity_top_k", RecallSettings.DEFAULT.entityTopK(), 1.0, true, 1_000.0,
                        strict),
                bounded(config, "recall.entity_sim_cut", RecallSettings.DEFAULT.entitySimCut(), 0.0, true, 1.0,
                        strict));
        DedupSettings dedup = new DedupSettings(
                bounded(config, "dedup.cosine_distance_max", DedupSettings.DEFAULT.cosineDistanceMax(), 0.0, true, 2.0,
                        strict),
                (int) bounded(config, "dedup.unique_token_weight", DedupSettings.DEFAULT.uniqueTokenWeight(), 0.0, true,
                        1_000.0, strict));
        // Zero is allowed on all three: each gate is an "or", so setting one to zero makes it always
        // true, which is how a workspace turns batching off. A workspace that wants every message
        // derived immediately is a real configuration, not a mistake.
        BatchSettings batch = new BatchSettings(
                (int) bounded(config, "batch.token_threshold", BatchSettings.DEFAULT.tokenThreshold(), 0.0, true,
                        1_000_000.0, strict),
                Duration.ofMinutes((long) bounded(config, "batch.max_age_minutes", 30, 0.0, true, 10_080.0, strict)),
                Duration.ofSeconds((long) bounded(config, "batch.idle_flush_seconds", 3, 0.0, true, 3_600.0, strict)));
        return new WorkspaceSettings(languageTag(config, strict), recall, dedup, batch,
                flag(config, "observe_me", true, strict), flag(config, "observe_others", true, strict));
    }

    private static String languageTag(Map<String, Object> config, boolean strict) {
        Object value = config.get("language");
        if (value == null) {
            return "und";
        }
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        if (strict) {
            throw new ConfigurationException("'language' expects a non-empty string, got " + value);
        }
        return "und";
    }

    private static FusionWeights weights(Map<String, Object> config, boolean strict) {
        Object raw = config.get("recall.weights");
        if (raw == null) {
            return FusionWeights.DEFAULT;
        }
        if (!(raw instanceof List<?> list) || list.size() != 6) {
            if (strict) {
                throw new ConfigurationException("'recall.weights' expects six numbers in the order"
                        + " [sem, kw, ent, reinf, rec, lvl], got " + raw);
            }
            return FusionWeights.DEFAULT;
        }
        double[] values = new double[6];
        for (int i = 0; i < 6; i++) {
            if (!(list.get(i) instanceof Number n)) {
                if (strict) {
                    throw new ConfigurationException(
                            "'recall.weights[" + i + "]' expects a number, got " + list.get(i));
                }
                return FusionWeights.DEFAULT;
            }
            values[i] = n.doubleValue();
        }
        try {
            return new FusionWeights(values[0], values[1], values[2], values[3], values[4], values[5]);
        } catch (RuntimeException e) {
            if (strict) {
                // The constructor's own message names the sum, which is the number the caller got
                // wrong; a fixed-denominator formula is the whole point of the weights summing to one.
                throw new ConfigurationException("'recall.weights' rejected: " + e.getMessage());
            }
            return FusionWeights.DEFAULT;
        }
    }

    /**
     * A numeric setting inside the range that can actually be honoured.
     *
     * @param minInclusive whether {@code min} itself is allowed; a half-life of zero is a division,
     *     a threshold of zero is simply no threshold
     */
    private static double bounded(Map<String, Object> config, String key, double fallback, double min,
            boolean minInclusive, double max, boolean strict) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number n)) {
            if (strict) {
                throw new ConfigurationException("'" + key + "' expects a number, got " + value);
            }
            return fallback;
        }
        double d = n.doubleValue();
        boolean aboveMin = minInclusive ? d >= min : d > min;
        if (!aboveMin || d > max || Double.isNaN(d)) {
            if (strict) {
                throw new ConfigurationException(
                        "'" + key + "' must be in " + (minInclusive ? "[" : "(") + min + ", " + max + "], got " + d);
            }
            return fallback;
        }
        return d;
    }

    private static boolean flag(Map<String, Object> config, String key, boolean fallback, boolean strict) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (strict) {
            throw new ConfigurationException("'" + key + "' expects true or false, got " + value);
        }
        return fallback;
    }
}
