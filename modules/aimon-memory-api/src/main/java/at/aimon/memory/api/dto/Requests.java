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

    /**
     * Ceiling on one roster request's peer list.
     *
     * <p>A hundred, because that is what the neighbouring list on this surface already costs: {@link
     * CreateMessages#messages} has carried {@code @Size(max = 100)} since it was written, and {@code
     * MessageIngestionService.MAX_BATCH} enforces the same number one layer down. The two lists have the
     * same shape — elements in one body, each of which turns into a fixed number of writes — so they
     * should not disagree about how many is too many. The immediate write cost is in fact the same on
     * both: {@code PeerRepository.getOrCreate} is an insert and a read and {@code
     * SessionPeerRepository.join} is an upsert and a window insert, so a hundred peers is four hundred
     * statements — and ingestion pays the same four per message, calling both for each speaker in the
     * batch. What makes a roster element the more expensive of the two is not this request but every
     * batch after it.
     *
     * <p>The second reason the number is not larger is downstream and quadratic. Every peer in a session
     * that observes the others adds a pair, and ADR 0006 fixes the cost at N + N(N−1) <em>extraction
     * calls per batch</em> — {@code docs/guide.md} uses a ten-person room, at a hundred calls per batch,
     * as the example worth thinking twice about, and the ADR names a fifty-person channel as the case
     * {@code observe_others} exists to switch off. A hundred is twice the largest room those documents
     * discuss, which is the headroom meant: the cap has to clear the rooms people really open without
     * being the thing that bounds the fan-out, because it cannot be — see below. Five hundred in one
     * body is not a roster anyone meant to send.
     *
     * <p>This bounds a <em>request</em>, not the roster: {@code POST} adds, so a caller determined to
     * assemble a thousand-peer session can still do it a hundred at a time. That is the intended
     * remaining hole — it costs a hundred round trips per hundred peers, which is a rate limiter's
     * problem rather than a validation one, and closing it here would mean reading the current roster
     * size on every add.
     */
    public static final int MAX_SESSION_PEERS = 100;

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
     *     <p>{@code @Size} is the second bound and a new one. {@code @NotEmpty} said the list could not be
     *     empty and nothing said it could not be enormous, so one body drove an unbounded number of
     *     writes through {@code HierarchyController.addSessionPeers} — four statements per element — and,
     *     through {@code ObserverResolver}, an unbounded quadratic term on every batch of messages the
     *     session later received. See {@link #MAX_SESSION_PEERS} for where the number comes from.
     *     <p><b>Refused, not truncated.</b> {@code Bounds} clamps the paging and limit parameters instead
     *     of rejecting them, and the reason it gives is that pagination tells the caller whether more
     *     remains. A roster has no such signal, and on {@code PUT} truncating would not merely drop the
     *     elements past the cap — {@code SessionPeerRepository.replace} closes the membership of everyone
     *     not named, so the peers silently discarded here would be removed from the session and lose
     *     their access to everything said in it. Clamping a list is destructive in a way clamping a
     *     number is not. {@link CreateMessages} already answers 400 to the hundred-and-first message.
     *     <p>{@code min = 1} is redundant against {@code @NotEmpty} for validation and load-bearing for
     *     the published schema, the same trap {@link NewMessage} documents one field over: springdoc
     *     derives {@code minItems} from whichever of the two it finds, and a bare {@code @Size(max = …)}
     *     would have replaced the {@code minItems: 1} this schema already publishes with {@code 0}.
     */
    public record AddSessionPeers(
            @Valid @NotEmpty @Size(min = 1, max = MAX_SESSION_PEERS) List<SessionPeerSpec> peers) {
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
     * as a user turn, so {@code "banana"} is accepted and silently becomes one. That was left as a
     * separate question when the declared constraints were made to run; it has since been asked, and
     * the answer is to keep it permissive.
     *
     * <p>What the coercion actually costs, measured rather than assumed. The blast radius is one
     * answer: {@code history} is read in {@code ChatController.question}, trimmed in
     * {@code DialecticService.requestFor} and sent as prompt messages. No path writes it, so a
     * mis-rolled turn shapes a single reply and is not stored, not embedded and not derived from.
     * A typo — {@code "assistnat"} — therefore hands the model its own previous answer as something
     * the user said, which degrades that reply and nothing beyond it. How much it degrades it is not
     * something this repository can measure: {@code AIMON_MEMORY_LLM_MODE} is {@code replay} in CI and
     * no live provider was called for this.
     *
     * <p>Two facts decide it against an enumeration. {@link at.aimon.memory.core.spi.llm.Role} has
     * exactly {@code USER} and {@code ASSISTANT} — there is no system role to reach, the system prompt
     * being {@code Prompts.dialectic(...)} and server-side — so {@code "system"} has nowhere better to
     * land than a user turn. An enumeration would 400 that request, and the only way for the caller to
     * get an answer would be to relabel the turn {@code user}, which is what this coercion already
     * does, one failed round trip earlier. And {@code docs/openapi.json} publishes {@code role} as
     * {@code {"type": "string", "minLength": 1}} and has never said more, so an enumeration is a
     * <em>new</em> promise that starts refusing requests which work today. By the standard
     * {@code CreateConclusion} above sets, a new promise needs measured harm behind it; misattributing
     * one turn of one prompt, unpersisted, is not that.
     *
     * <p>Accept-and-say-so is the third option, and it is not the same choice as an enumeration. A log
     * line reaches an operator rather than the caller, so it does not close the gap that is actually
     * open: {@code Dtos.ChatResponse} echoes text, iterations, the limit flag and the tool calls, never
     * the history it answered against. Echoing the resolved roles there would close it, but it adds a
     * field to a published schema for a fault the caller can already find by reading the request it
     * sent. So the gap is closed in {@code docs/guide.md} rather than by a constraint or a field.
     * Revisit if a system role ever becomes reachable, or if the coercion is ever shown to cost more
     * than one reply.
     */
    public record ChatTurn(@NotBlank String role, @NotBlank String content) {
    }

    public record ScheduleDream(@NotBlank String observer, @NotBlank String observed, String type) {
    }

    public record IssueToken(@NotBlank String scope, String workspace, String peer, String session,
            Boolean allowMemberRead, Long lifetimeSeconds) {
    }
}
