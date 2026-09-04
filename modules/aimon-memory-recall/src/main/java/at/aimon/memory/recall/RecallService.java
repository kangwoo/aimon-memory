package at.aimon.memory.recall;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import at.aimon.memory.core.model.Conclusion;
import at.aimon.memory.core.model.EntityMatch;
import at.aimon.memory.core.model.ScoredConclusion;
import at.aimon.memory.core.spi.Analyzer;
import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.core.spi.Embedder;
import at.aimon.memory.recall.signal.EntityBoost;
import at.aimon.memory.store.WorkspaceSettings;
import at.aimon.memory.store.WorkspaceSettingsService;
import at.aimon.memory.store.repo.ConclusionRepository;
import at.aimon.memory.store.repo.EntityRepository;
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

    private final ConclusionRepository conclusions;
    private final EntityRepository entities;
    private final WorkspaceSettingsService settings;
    private final Embedder embedder;
    private final Clock clock;

    public RecallService(ConclusionRepository conclusions, EntityRepository entities, WorkspaceSettingsService settings,
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

        int oversampled = request.limit() * Math.max(1, workspaceSettings.recall().oversample());

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
        Bm25.CorpusStats stats = conclusions.corpusStats(request.pair(), queryTerms);
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
