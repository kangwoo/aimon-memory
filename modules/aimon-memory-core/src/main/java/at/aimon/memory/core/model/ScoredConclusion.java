package at.aimon.memory.core.model;

/** A recall hit: the conclusion, its fused score, and the breakdown that produced it. */
public record ScoredConclusion(Conclusion conclusion, double score, Explain explain) {

    /** Used by the store's single-signal queries, before fusion has anything to explain. */
    public static ScoredConclusion raw(Conclusion conclusion, double score) {
        return new ScoredConclusion(conclusion, score, null);
    }

    public String id() {
        return conclusion.id();
    }
}
