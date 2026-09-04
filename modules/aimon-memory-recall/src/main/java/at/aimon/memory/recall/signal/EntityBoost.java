package at.aimon.memory.recall.signal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import at.aimon.memory.core.model.EntityMatch;

/**
 * The {@code ent} signal.
 *
 * <p>Two ideas, both worth stating.
 *
 * <p>{@code countWeight = 1/(1 + 0.001(n-1)^2)} discounts entities attached to many conclusions. An
 * entity linked to everything in a pair discriminates nothing — it is the entity layer's version of
 * IDF. Quadratic and gently scaled, so a dozen links barely register and several hundred do.
 *
 * <p>The 0.5 similarity floor is a hard cut, not a taper. Vector search always returns its top-k, so
 * without a floor every query "matches" some entity and contributes noise to every candidate.
 */
public final class EntityBoost {

    /**
     * @param byConclusion conclusion id to its boost in [0,1]
     * @param namesByConclusion which entities produced it, for the explain payload
     */
    public record Result(Map<String, Double> byConclusion, Map<String, List<String>> namesByConclusion) {

        public static Result empty() {
            return new Result(Map.of(), Map.of());
        }

        public double boostFor(String conclusionId) {
            return byConclusion.getOrDefault(conclusionId, 0.0);
        }

        public List<String> namesFor(String conclusionId) {
            return namesByConclusion.getOrDefault(conclusionId, List.of());
        }
    }

    /** An (entity, conclusion) edge inside the pair. */
    public record Link(String entityId, String conclusionId) {
    }

    private EntityBoost() {
    }

    public static Result compute(List<EntityMatch> matches, List<Link> links, double similarityFloor) {
        Map<String, EntityMatch> eligible = new LinkedHashMap<>();
        for (EntityMatch match : matches) {
            if (match.similarity() >= similarityFloor) {
                eligible.put(match.entity().id(), match);
            }
        }
        if (eligible.isEmpty() || links.isEmpty()) {
            return Result.empty();
        }

        Map<String, Double> boosts = new LinkedHashMap<>();
        Map<String, Set<String>> names = new LinkedHashMap<>();
        for (Link link : links) {
            EntityMatch match = eligible.get(link.entityId());
            if (match == null) {
                continue;
            }
            // Max, not sum: two entities matching the same conclusion is evidence it is relevant, not
            // evidence it is twice as relevant. Summing would let a conclusion mentioning many places
            // outrank one that answers the question.
            boosts.merge(link.conclusionId(), clamp(match.boost()), Math::max);
            names.computeIfAbsent(link.conclusionId(), k -> new LinkedHashSet<>()).add(match.entity().nameDisplay());
        }

        Map<String, List<String>> ordered = new LinkedHashMap<>();
        names.forEach((conclusionId, set) -> {
            List<String> sorted = new ArrayList<>(set);
            sorted.sort(String::compareTo);
            ordered.put(conclusionId, List.copyOf(sorted));
        });
        return new Result(Map.copyOf(boosts), Map.copyOf(ordered));
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
