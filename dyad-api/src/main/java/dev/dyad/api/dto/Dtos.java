package dev.dyad.api.dto;

import dev.dyad.core.model.Conclusion;
import dev.dyad.core.model.ConclusionEvent;
import dev.dyad.core.model.Explain;
import dev.dyad.core.model.Message;
import dev.dyad.core.model.Page;
import dev.dyad.core.model.ScoredConclusion;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wire shapes.
 *
 * <p>Separate from the domain records so that a column rename is not an API break, and so that
 * nothing internal leaks by default — {@code content_norm}, {@code content_analyzed} and the
 * embedding have no business on the wire, and they would all be there if the domain type were
 * serialised directly.
 */
public final class Dtos {

    private Dtos() {}

    public record PageResponse<T>(List<T> items, int page, int size, long total, boolean hasNext) {
        public static <T, R> PageResponse<R> of(Page<T> page, java.util.function.Function<T, R> map) {
            return new PageResponse<>(
                    page.items().stream().map(map).toList(),
                    page.page(),
                    page.size(),
                    page.total(),
                    page.hasNext());
        }
    }

    public record ErrorResponse(String code, String message) {}

    public record WorkspaceResponse(
            String name, Map<String, Object> metadata, Map<String, Object> configuration, Instant createdAt) {}

    public record PeerResponse(
            String name, Map<String, Object> metadata, Map<String, Object> configuration, Instant createdAt) {}

    public record SessionResponse(
            String name, boolean isActive, Map<String, Object> metadata, Instant createdAt) {}

    public record SessionPeerResponse(
            String peer, Boolean observeMe, Boolean observeOthers, Instant joinedAt, Instant leftAt) {}

    public record MessageResponse(
            long id, String peer, String content, long seq, int tokenCount,
            Map<String, Object> metadata, Instant createdAt) {

        public static MessageResponse of(Message message) {
            return new MessageResponse(
                    message.id(),
                    message.peerName(),
                    message.content(),
                    message.seqInSession(),
                    message.tokenCount(),
                    message.metadata(),
                    message.createdAt());
        }
    }

    public record ExplainResponse(
            double sem, double kw, double ent, double reinf, double rec, double lvl,
            List<Double> weights, List<String> matchedEntities) {

        public static ExplainResponse of(Explain explain) {
            double[] weights = explain.weights().asArray();
            List<Double> boxed = new java.util.ArrayList<>(weights.length);
            for (double weight : weights) {
                boxed.add(weight);
            }
            return new ExplainResponse(
                    explain.sem(), explain.kw(), explain.ent(), explain.reinf(), explain.rec(), explain.lvl(),
                    List.copyOf(boxed), explain.matchedEntities());
        }
    }

    public record ConclusionResponse(
            String id,
            String observer,
            String observed,
            String session,
            String content,
            String level,
            Double confidence,
            List<String> sourceIds,
            List<Long> messageIds,
            int timesDerived,
            Instant lastReinforcedAt,
            Instant createdAt,
            Instant expiresAt) {

        public static ConclusionResponse of(Conclusion c) {
            return new ConclusionResponse(
                    c.id(),
                    c.pair().observer(),
                    c.pair().observed(),
                    c.sessionName(),
                    c.content(),
                    c.level().wire(),
                    c.confidence(),
                    c.sourceIds(),
                    c.messageIds(),
                    c.timesDerived(),
                    c.lastReinforcedAt(),
                    c.createdAt(),
                    c.expiresAt());
        }
    }

    public record RecallHitResponse(ConclusionResponse conclusion, double score, ExplainResponse explain) {

        public static RecallHitResponse of(ScoredConclusion hit) {
            return new RecallHitResponse(
                    ConclusionResponse.of(hit.conclusion()),
                    hit.score(),
                    hit.explain() == null ? null : ExplainResponse.of(hit.explain()));
        }
    }

    public record RecallResponseBody(
            List<RecallHitResponse> hits, String analyzedQuery, int candidatesConsidered) {}

    public record EventResponse(
            long id, String conclusionId, String event, String actor,
            String beforeContent, String afterContent, Map<String, Object> detail, Instant createdAt) {

        public static EventResponse of(ConclusionEvent e) {
            return new EventResponse(
                    e.id(), e.conclusionId(), e.event().wire(), e.actor().wire(),
                    e.beforeContent(), e.afterContent(), e.detail(), e.createdAt());
        }
    }

    public record ContextResponse(
            String summary,
            List<MessageResponse> messages,
            long messagesStartSeq,
            int summaryTokens,
            int messageTokens,
            int tokenBudget,
            String representation) {}

    public record ChatResponse(
            String answer, int iterations, boolean stoppedAtLimit, List<ToolCallResponse> toolCalls) {}

    public record ToolCallResponse(String name, String arguments, boolean failed) {}

    public record ProvenanceResponse(
            String entity, List<ProvenanceEntry> conclusions) {}

    /**
     * @param unresolvedPremiseIds premises that have been deleted; named so a shortened chain is
     *     visibly shortened rather than looking complete
     */
    public record ProvenanceEntry(
            ConclusionResponse conclusion,
            List<ConclusionResponse> premises,
            List<MessageResponse> sourceMessages,
            List<String> unresolvedPremiseIds) {}

    public record DreamResponse(
            String id, String observer, String observed, String type, String status,
            int produced, String error, Instant createdAt, Instant completedAt) {}

    public record PeerCardResponse(String observer, String observed, List<String> lines, Instant updatedAt) {}

    public record TokenResponse(String token, String scope, Instant expiresAt) {}
}
