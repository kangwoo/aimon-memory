package at.aimon.memory.api.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** Request bodies. Validation is declarative so a bad body is a 400 before any handler runs. */
public final class Requests {

    /**
     * Ceiling on one message's text, in characters.
     *
     * <p>32k is 8191 tokens at the usual four-characters-per-token approximation — the point where
     * {@code OpenAiEmbedder} truncates its input, and therefore the point past which stored text stops
     * being reachable by semantic recall. It is generous for a conversational turn by a wide margin: a
     * long one is hundreds of characters, and the whole batch of a hundred still fits in a request a
     * server can hold.
     */
    public static final int MAX_CONTENT_CHARS = 32_000;

    private Requests() {
    }

    public record CreateWorkspace(Map<String, Object> metadata, Map<String, Object> configuration) {
    }

    public record CreatePeer(Map<String, Object> metadata, Map<String, Object> configuration) {
    }

    public record CreateSession(Map<String, Object> metadata, Map<String, Object> configuration) {
    }

    public record UpdateConfiguration(@NotEmpty Map<String, Object> configuration) {
    }

    /**
     * @param peers {@code @Valid} for the reason {@link CreateMessages} carries it: without it Bean
     *     Validation stops at the list and {@link SessionPeerSpec}'s {@code @NotBlank} is never
     *     evaluated. This one is a genuine change in what the endpoint accepts, not a relabelling —
     *     {@code PeerRepository.getOrCreate} builds no {@code PairKey}, so nothing downstream was
     *     checking the name either. {@code {"peers":[{"peer":"   "}]}} answered 200 and left a peer row
     *     literally named three spaces, joined to the session and eligible to observe every message in
     *     it. A {@code null} peer was refused, but by a NOT NULL constraint reported as a 409.
     */
    public record AddSessionPeers(@Valid @NotEmpty List<SessionPeerSpec> peers) {
    }

    public record SessionPeerSpec(@NotBlank String peer, Boolean observeMe, Boolean observeOthers) {
    }

    /**
     * @param messages {@code @Valid} is load-bearing, not decoration. Without it Bean Validation stops
     *     at this list and never descends into the elements, so every constraint on {@link NewMessage}
     *     was inert: a message with blank content was accepted with a 200 and stored. The blank
     *     <em>peer</em> that looked like it was being rejected was in fact refused much deeper, by the
     *     key encoder, and reported as {@code bad_key}.
     */
    public record CreateMessages(@Valid @NotEmpty @Size(max = 100) List<NewMessage> messages) {
    }

    /**
     * One conversational turn.
     *
     * <p>The batch has been capped at a hundred since it was written; the message itself was not, and
     * the two together are what bound a request. They are also what bound a <em>response</em>: {@code
     * GET /context} returns whole messages up to {@code ContextService.MAX_WINDOW} of them, so with no
     * limit here the size of that reply is decided by whatever the largest stored message happens to
     * be. Capping the batch alone left half the arithmetic open.
     *
     * <p>{@link #MAX_CONTENT_CHARS} rather than a token count, because validation runs before any
     * analyzer does and a character length is the thing a caller can predict. The number is the
     * embedder's own ceiling read back through the usual four-characters-per-token approximation:
     * {@code OpenAiEmbedder} truncates its input at 8191 tokens, so text past roughly this length is
     * cut before it ever becomes a vector. Storing more than that means storing a message whose tail
     * semantic recall can never see — worse than refusing it, because nothing says so.
     *
     * <p>{@code min = 1} is redundant against {@code @NotBlank} for validation and not for the
     * description: springdoc derives {@code minLength} from whichever of the two it finds, and a bare
     * {@code @Size(max = …)} made the published schema say an empty message was acceptable. The
     * constraint had not changed; only the document describing it had, in the direction of being
     * wrong.
     */
    public record NewMessage(@NotBlank String peer, @NotBlank @Size(min = 1, max = MAX_CONTENT_CHARS) String content,
            Map<String, Object> metadata) {
    }

    public record RecallQuery(@NotBlank String query, @NotBlank String observer, @NotBlank String observed,
            Integer limit, Map<String, Object> filter, Double threshold, Boolean explain) {
    }

    public record SearchMessages(Map<String, Object> filter, Integer page, Integer size) {
    }

    /**
     * Direct injection, for facts a caller already knows and does not want extracted.
     *
     * <p>{@code List<@NotBlank @UsableName String>} is a container-element constraint, and unlike the
     * {@code @Valid} additions elsewhere in this file it is a <em>new</em> promise rather than a
     * restored one. The
     * other three — {@code NewMessage}, {@code SessionPeerSpec}, {@code ChatTurn} — already published
     * {@code minLength: 1} in {@code docs/openapi.json} and simply were not enforcing it. Nothing has
     * ever been said about the elements of this list, so a caller sending a blank one is not violating
     * a documented contract; it is being held to a new one, and the schema moves with it.
     *
     * <p>What earns the new constraint is measured harm rather than tidiness. A blank name reaches
     * {@code EntityPipeline} and used to become a node whose {@code name_norm} and {@code name_display}
     * are both empty — one node, since every blank normalises to the same key, accumulating an edge
     * from every unrelated conclusion that carried a stray empty string, sitting in the vector index,
     * and handing the {@code ent} signal to all of them together when a query landed near it. A
     * {@code null} element was worse: {@code List.copyOf} inside {@code ConclusionDraft} rejects it, so
     * the request came back 500 with a stack trace logged at ERROR — a client's malformed array
     * counted as a server fault.
     *
     * <p>The pipeline now drops blank names on its own, because most of them come from a model and a
     * work unit cannot be handed back to its author. This constraint is the stricter answer for the one
     * caller that can be told: an API client that sends {@code ["서울", ""]} has a bug in how it built
     * that array, and a 400 naming the field is more use to it than an entity silently going missing.
     *
     * <p>{@link UsableName} sits alongside {@code @NotBlank} because the two layers were defining blank
     * differently: {@code @NotBlank} is {@link String#trim()} and the pipeline is {@link String#isBlank()},
     * so a whitespace character above {@code U+0020} was accepted here and dropped there — the silent
     * disappearance this constraint exists to prevent, arriving through the constraint itself.
     * {@code @NotBlank} stays because it rejects {@code null} and because it is what publishes
     * {@code minLength: 1}.
     */
    public record CreateConclusion(@NotBlank String observer, @NotBlank String observed, String session,
            @NotBlank String content, List<@NotBlank @UsableName String> entities, String expiresAt) {
    }

    /**
     * @param history prior turns of this chat, oldest first. {@code @Valid} again, and here it is the
     *     change with real consequences: a blank or null turn was passed straight through
     *     {@code ChatController.question} into an {@code LlmMessage} and sent to the provider. Refused
     *     rather than filtered out — see {@link ChatTurn}.
     */
    public record ChatRequest(@NotBlank String question, @NotBlank String observer, @NotBlank String observed,
            String session, String reasoningLevel, @Valid List<ChatTurn> history, Map<String, Object> responseFormat) {
    }

    /**
     * One prior turn of the conversation.
     *
     * <p>Both constraints were declared from the start and neither was ever evaluated, because the list
     * that holds these was not marked {@code @Valid}. What hid it is that nothing here fails loudly: a
     * blank turn is not a crash, it is an extra message in a prompt, and the answer that comes back is
     * merely computed against a transcript nobody meant to send.
     *
     * <p><b>Refused, not silently dropped.</b> Skipping empty turns was the alternative and it is the
     * worse one. This record has no way to express a turn that legitimately carries no text — there is
     * no tool-call field — so a blank {@code content} is always a client assembling its transcript
     * wrongly, and dropping it would answer a different conversation from the one asked about with
     * nothing in the response saying so. That is the failure this codebase refuses everywhere else it
     * appears: a filter whose predicate was quietly discarded, a configuration key that stored cleanly
     * and tuned nothing.
     *
     * <p>It is also not harmless downstream. Anthropic rejects an empty text block outright, and
     * {@code HttpSupport} classifies that 400 as {@code llm_rejected} — which the fallback chain
     * deliberately does not retry and {@code ApiExceptionHandler} reports as a 500. Passing a blank
     * turn through turns a malformed request body into a server fault in the error-rate metric.
     *
     * <p>{@code role} is only checked for being present. Anything that is not {@code assistant} is read
     * as a user turn, so {@code "banana"} is accepted and silently becomes one; narrowing that to an
     * enumeration is a separate change from making the declared constraints run.
     */
    public record ChatTurn(@NotBlank String role, @NotBlank String content) {
    }

    public record ScheduleDream(@NotBlank String observer, @NotBlank String observed, String type) {
    }

    public record IssueToken(@NotBlank String scope, String workspace, String peer, String session,
            Boolean allowMemberRead, Long lifetimeSeconds) {
    }
}
