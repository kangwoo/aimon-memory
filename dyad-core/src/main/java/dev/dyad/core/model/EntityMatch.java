package dev.dyad.core.model;

/**
 * An entity matched against a query vector.
 *
 * @param linkedConclusions how many conclusions the entity is attached to; feeds {@code countWeight}
 *     so that an entity linked to everything stops meaning anything
 */
public record EntityMatch(EntityRef entity, double similarity, int linkedConclusions) {

    /** Entities linked to more conclusions discriminate less. Quadratic, gentle at first. */
    public double countWeight() {
        int n = Math.max(1, linkedConclusions);
        return 1.0 / (1.0 + 0.001 * Math.pow(n - 1.0, 2));
    }

    /** The entity's contribution to the {@code ent} signal, before the max across matches. */
    public double boost() {
        return similarity * countWeight();
    }
}
