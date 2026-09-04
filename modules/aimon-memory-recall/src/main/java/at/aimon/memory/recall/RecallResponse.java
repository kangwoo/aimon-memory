package at.aimon.memory.recall;

import java.util.List;

import at.aimon.memory.core.model.ScoredConclusion;

/**
 * @param analyzedQuery what the analyzer made of the query; the single most useful field when a
 *     Korean query returns nothing and nobody can see why
 * @param candidatesConsidered size of the union before fusion, for judging whether oversampling is
 *     doing its job
 */
public record RecallResponse(List<ScoredConclusion> hits, String analyzedQuery, int candidatesConsidered) {

    public RecallResponse {
        hits = List.copyOf(hits);
    }

    public static RecallResponse empty(String analyzedQuery) {
        return new RecallResponse(List.of(), analyzedQuery, 0);
    }
}
