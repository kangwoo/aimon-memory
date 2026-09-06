package at.aimon.memory.recall;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import at.aimon.memory.core.model.Conclusion;
import at.aimon.memory.core.model.CorpusStats;
import at.aimon.memory.core.model.EntityMatch;
import at.aimon.memory.core.model.ScoredConclusion;
import at.aimon.memory.core.spi.Analyzer;
import at.aimon.memory.core.spi.ConclusionStore;
import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.core.spi.Embedder;
import at.aimon.memory.core.spi.EntityStore;
import at.aimon.memory.recall.signal.EntityBoost;
import at.aimon.memory.store.WorkspaceSettings;
import at.aimon.memory.store.WorkspaceSettingsService;
import at.aimon.memory.text.Bm25;

/**
 * Tier 1 recall: structured retrieval with no model in the loop.
 *
 * <p>This is the layer the design exists for. One source system has a ranking engine and no notion of
 * a reasoning tree; the other has the tree and answers even trivial lookups by running an agent. Most
 * questions asked of a memory system are lookups, and they should be fast, cheap and the same answer
 * every time.
 *
 * <p>Both signals are computed for every candidate, not only for the ones their own path returned. A
 * conclusion the vector search found still has a real BM25 score, and scoring it zero would let the
 * ranking depend on which path happened to surface a row first.
 */
@Service
public class RecallService {

    /**
     * Ceiling on the rows one signal path may fetch before fusion.
     *
     * <p>{@code limit * oversample} is the number that decides the cost of a recall, and until now
     * nothing bounded it. Both factors were bounded separately — {@link RecallRequest#MAX_LIMIT} is 100
     * and {@code WorkspaceSettingsService} rejects an {@code oversample} above 100 — and the product of
     * two bounded numbers is ten thousand, per path, of which there are two. Twenty thousand
     * {@code Conclusion} rows then become one {@code LinkedHashMap}, one {@code id = ANY (?)} array for
     * the missing semantic scores, one BM25 pass in Java, one {@code linksAmong} array, and one sort —
     * all to return at most a hundred. The javadoc on {@code Bounds} says recall is worse than linear in
     * the limit for exactly this reason; the multiplier it names was itself unbounded in the product.
     *
     * <p>A thousand, because that is the fetch width this system already permits a recall path: {@code
     * recall.entity_top_k} is validated to {@code [1, 1000]} in the same {@code parse()} call, for the
     * same kind of number — how many rows the entity signal pulls before it contributes. The semantic
     * and keyword paths had no equivalent. They do now, and it is the one the entity path has had all
     * along rather than a new number invented for them.
     *
     * <p>Nothing that was configurable stops being configurable. The defaults are 10 × 4 = 40, twenty-five
     * times below this; the maximum limit at the default oversample is 400; and {@code oversample: 100}
     * with the default limit of 10 lands exactly on the ceiling and is honoured in full. The clamp
     * engages only where the product exceeds a thousand.
     *
     * <p>The response cannot announce the clamp. {@code RecallResponse.candidatesConsidered} is the
     * size of the union the two paths actually produced — that is what its own javadoc says it is — and
     * not the width they were asked for, so it narrows when the clamp engages without reporting that it
     * did. It is still the field to watch for whether oversampling is earning its cost, but the ceiling
     * cannot be read off it. So the clamp says so in the log instead: silently changing a ranking and
     * leaving no trace of it is the failure {@code WorkspaceSettingsService} already writes down one
     * module over, where a configuration that cannot be honoured falls back and logs, "since the symptom
     * otherwise is only that tuning had no effect". That is exactly the symptom here.
     *
     * <p>Clamped rather than rejected, unlike the other out-of-range configuration values, because this
     * one can be honoured. {@code bounded()} validates a multiplier and cannot see the limit it will be
     * multiplied by, so a product ceiling is not expressible at the write boundary; refusing
     * {@code oversample: 100} there would refuse a setting that still does exactly what it says for
     * every limit up to ten.
     */
    public static final int MAX_CANDIDATES = 1_000;

    private static final Logger log = LoggerFactory.getLogger(RecallService.class);

    private final ConclusionStore conclusions;
    private final EntityStore entities;
    private final WorkspaceSettingsService settings;
    private final Embedder embedder;
    private final Clock clock;

    public RecallService(ConclusionStore conclusions, EntityStore entities, WorkspaceSettingsService settings,
            Embedder embedder, Clock clock) {
        this.conclusions = conclusions;
        this.entities = entities;
        this.settings = settings;
        this.embedder = embedder;
        this.clock = clock;
    }

    public RecallResponse recall(RecallRequest request) {
        String workspace = request.pair().workspaceName();
        WorkspaceSettings workspaceSettings = settings.forWorkspace(workspace);
        Analyzer analyzer = settings.analyzerFor(workspace);

        List<String> queryTerms = analyzer.tokens(request.query());
        String analyzed = String.join(" ", queryTerms);
        float[] queryVector = embedder.embed(request.query(), EmbedPurpose.QUERY);

        int requested = request.limit() * Math.max(1, workspaceSettings.recall().oversample());
        int oversampled = Math.min(MAX_CANDIDATES, requested);
        if (requested > oversampled) {
            // Debug rather than warn: the setting is legal, the request is answered, and this is a read
            // path that would repeat the line on every query. Debug is the level the same kind of
            // silent narrowing already uses in `EntityPipeline`, and it is reachable through
            // AIMON_MEMORY_LOG_LEVEL, which is what an operator asking why a ranking moved has to hand.
            log.debug("recall for {} asked {} candidates per path (limit {} x oversample {}); clamped to {}", workspace,
                    requested, request.limit(), workspaceSettings.recall().oversample(), oversampled);
        }

        Map<String, Conclusion> candidates = new LinkedHashMap<>();
        Map<String, Double> semantic = new LinkedHashMap<>();
        for (ScoredConclusion hit : conclusions.semantic(request.pair(), queryVector, oversampled, request.filter())) {
            candidates.put(hit.id(), hit.conclusion());
            semantic.put(hit.id(), hit.score());
        }
        Map<String, Double> keyword = new LinkedHashMap<>();
        if (!analyzed.isBlank()) {
            for (ScoredConclusion hit : conclusions.keyword(request.pair(), analyzed, oversampled, request.filter())) {
                candidates.putIfAbsent(hit.id(), hit.conclusion());
                keyword.put(hit.id(), hit.score());
            }
        }
        if (candidates.isEmpty()) {
            return RecallResponse.empty(analyzed);
        }

        List<String> ids = List.copyOf(candidates.keySet());
        fillMissingSemantic(request, queryVector, ids, semantic);
        fillMissingKeyword(request, queryTerms, candidates, keyword);

        EntityBoost.Result boosts = entityBoosts(request, workspaceSettings, queryVector, ids);

        FusionRanker ranker = new FusionRanker(request.threshold() == null
                ? workspaceSettings.recall()
                : workspaceSettings.recall().withThreshold(request.threshold()));

        List<ScoredConclusion> ranked = ranker.rank(List.copyOf(candidates.values()), semantic, keyword, boosts,
                queryTerms.size(), clock.instant(), request.limit());

        if (!request.includeExplain()) {
            ranked = ranked.stream().map(h -> new ScoredConclusion(h.conclusion(), h.score(), null)).toList();
        }
        return new RecallResponse(ranked, analyzed, candidates.size());
    }

    private void fillMissingSemantic(RecallRequest request, float[] queryVector, List<String> ids,
            Map<String, Double> semantic) {
        List<String> missing = ids.stream().filter(id -> !semantic.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            semantic.putAll(conclusions.semanticScores(request.pair(), queryVector, missing));
        }
    }

    private void fillMissingKeyword(RecallRequest request, List<String> queryTerms, Map<String, Conclusion> candidates,
            Map<String, Double> keyword) {
        List<String> missing = candidates.keySet().stream().filter(id -> !keyword.containsKey(id)).toList();
        if (missing.isEmpty() || queryTerms.isEmpty()) {
            return;
        }
        CorpusStats stats = conclusions.corpusStats(request.pair(), queryTerms);
        for (String id : missing) {
            Conclusion candidate = candidates.get(id);
            keyword.put(id, Bm25.score(queryTerms, tokenize(candidate.contentAnalyzed()), stats));
        }
    }

    private EntityBoost.Result entityBoosts(RecallRequest request, WorkspaceSettings workspaceSettings,
            float[] queryVector, List<String> ids) {
        List<EntityMatch> matches = entities.match(request.pair(), queryVector,
                workspaceSettings.recall().entityTopK());
        if (matches.isEmpty()) {
            return EntityBoost.Result.empty();
        }
        List<String> entityIds = matches.stream().map(m -> m.entity().id()).toList();
        List<EntityBoost.Link> links = entities
                .linksAmong(request.pair().workspaceName(), request.pair(), entityIds, ids).stream()
                .map(l -> new EntityBoost.Link(l.entityId(), l.conclusionId())).toList();
        return EntityBoost.compute(matches, links, workspaceSettings.recall().entitySimCut());
    }

    private static List<String> tokenize(String analyzed) {
        if (analyzed == null || analyzed.isBlank()) {
            return List.of();
        }
        return new ArrayList<>(List.of(analyzed.trim().split("\\s+")));
    }
}
