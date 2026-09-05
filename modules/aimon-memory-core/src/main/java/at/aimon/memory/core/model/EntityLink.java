package at.aimon.memory.core.model;

/**
 * One edge of the entity graph: this entity is mentioned by this conclusion.
 *
 * <p>Moved out of {@code EntityRepository} so that {@code EntityStore.linksAmong} can be part of the
 * SPI. A nested record on the concrete class made the return type of a boundary-crossing method a
 * detail of one implementation, which is the shape that kept the SPI from being the seam.
 *
 * <p>The pair is not a field. Edges carry it in the database — that is what keeps two peers' views of
 * the same entity apart — but every query that returns these is already pair-scoped, so repeating it
 * on each row would be answering a question the caller has just asked.
 */
public record EntityLink(String entityId, String conclusionId) {
}
