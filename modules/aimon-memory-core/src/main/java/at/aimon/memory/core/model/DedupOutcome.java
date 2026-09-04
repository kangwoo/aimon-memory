package at.aimon.memory.core.model;

/**
 * What the three-stage dedup decided.
 *
 * @param kind which branch fired
 * @param conclusionId the row that now holds the fact — the new id for INSERTED/REPLACED, the
 *     surviving id for REINFORCED
 * @param replacedId the soft-deleted row, only for REPLACED
 * @param stage which dedup stage matched (0 when nothing matched)
 * @param similarity cosine similarity of the match, NaN when the match was not semantic
 */
public record DedupOutcome(Kind kind, String conclusionId, String replacedId, int stage, double similarity) {

    public enum Kind {
        /** Nothing matched — a genuinely new fact. */
        INSERTED,
        /** An existing row said the same thing at least as well; its counter went up. */
        REINFORCED,
        /** The new phrasing carried more information; the old row was soft-deleted. */
        REPLACED
    }

    public static DedupOutcome inserted(String id) {
        return new DedupOutcome(Kind.INSERTED, id, null, 0, Double.NaN);
    }

    public static DedupOutcome reinforced(String id, int stage, double similarity) {
        return new DedupOutcome(Kind.REINFORCED, id, null, stage, similarity);
    }

    public static DedupOutcome replaced(String newId, String replacedId, double similarity) {
        return new DedupOutcome(Kind.REPLACED, newId, replacedId, 3, similarity);
    }
}
