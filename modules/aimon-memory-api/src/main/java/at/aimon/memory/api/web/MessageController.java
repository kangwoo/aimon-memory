package at.aimon.memory.api.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import at.aimon.memory.api.Bounds;
import at.aimon.memory.api.dto.Dtos;
import at.aimon.memory.api.dto.Requests;
import at.aimon.memory.api.security.ForbiddenException;
import at.aimon.memory.api.security.MemoryPrincipal;
import at.aimon.memory.core.filter.Filter;
import at.aimon.memory.core.key.WorkUnitKey;
import at.aimon.memory.engine.context.ContextService;
import at.aimon.memory.engine.ingest.MessageIngestionService;
import at.aimon.memory.store.repo.MessageRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Message ingestion, search, and Tier 0 context. */
@RestController
@Tag(name = "messages", description = "Ingestion, search, and Tier 0 context. Posting a message starts derivation.")
public class MessageController {

    /** Upper bound on {@code ?wait=derive}. Past this the caller gets what exists rather than a hang. */
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration WAIT_POLL = Duration.ofMillis(100);

    private final MessageIngestionService ingestion;
    private final MessageRepository messages;
    private final ContextService context;

    public MessageController(MessageIngestionService ingestion, MessageRepository messages, ContextService context) {
        this.ingestion = ingestion;
        this.messages = messages;
        this.context = context;
    }

    /**
     * Store messages and queue derivation.
     *
     * <p>{@code wait=derive} blocks until the queued work units drain, which is the answer to
     * read-your-writes without giving up batching for everyone. It is per request on purpose: the
     * global equivalent — a flag that makes every write synchronous — costs the batching win across
     * the whole deployment to serve the few callers that need it.
     */
    @Operation(summary = "Store messages and queue derivation")
    @PostMapping("/v1/workspaces/{workspace}/sessions/{session}/messages")
    public List<Dtos.MessageResponse> create(@PathVariable String workspace, @PathVariable String session,
            MemoryPrincipal principal, @RequestParam(required = false) String wait,
            @Valid @RequestBody Requests.CreateMessages body) {

        // The speaker arrives in the body and the interceptor only sees path variables, so without
        // this a peer token could post messages signed with someone else's name — stored as theirs,
        // fanned out into every observer's memory, and derived into conclusions about them, with
        // nothing in the audit trail recording who actually made the call. A token that names no peer
        // (a session token, which is scoped to a conversation rather than a participant) still speaks
        // for everyone in its session; that is what it is for.
        for (Requests.NewMessage message : body.messages()) {
            if (!principal.canSpeakAs(message.peer())) {
                throw new ForbiddenException(
                        "token is scoped to a different peer and cannot post messages as " + message.peer());
            }
        }

        List<MessageIngestionService.IncomingMessage> incoming = body.messages().stream()
                .map(m -> new MessageIngestionService.IncomingMessage(m.peer(), m.content(), m.metadata())).toList();
        MessageIngestionService.IngestResult result = ingestion.ingest(workspace, session, incoming);

        if ("derive".equalsIgnoreCase(wait)) {
            awaitDerivation(result.queued());
        }
        return result.messages().stream().map(Dtos.MessageResponse::of).toList();
    }

    private void awaitDerivation(List<WorkUnitKey> keys) {
        Instant deadline = Instant.now().plus(WAIT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (ingestion.isDrained(keys)) {
                return;
            }
            try {
                Thread.sleep(WAIT_POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // Timing out is not an error: the messages are stored and the work is queued. The caller
        // simply does not get to see the conclusions in this response.
    }

    @Operation(summary = "List a session's messages")
    @GetMapping("/v1/workspaces/{workspace}/sessions/{session}/messages")
    public Dtos.PageResponse<Dtos.MessageResponse> list(@PathVariable String workspace, @PathVariable String session,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return Dtos.PageResponse.of(
                messages.search(workspace, session, Filter.ALL, Bounds.page(page), Bounds.size(size)),
                Dtos.MessageResponse::of);
    }

    @Operation(summary = "Search a session's messages")
    @PostMapping("/v1/workspaces/{workspace}/sessions/{session}/messages/search")
    public Dtos.PageResponse<Dtos.MessageResponse> search(@PathVariable String workspace, @PathVariable String session,
            @RequestBody(required = false) Requests.SearchMessages body) {
        Requests.SearchMessages request = body == null ? new Requests.SearchMessages(null, 0, 50) : body;
        return Dtos.PageResponse.of(messages.search(workspace, session, Filter.parse(request.filter()),
                Bounds.page(request.page() == null ? 0 : request.page()),
                Bounds.size(request.size() == null ? 50 : request.size())), Dtos.MessageResponse::of);
    }

    /** Tier 0: summary plus recent messages inside a token budget. No model, no vector search. */
    @Operation(summary = "Tier 0: summary plus recent messages inside a token budget")
    @GetMapping("/v1/workspaces/{workspace}/sessions/{session}/context")
    public Dtos.ContextResponse context(@PathVariable String workspace, @PathVariable String session,
            @RequestParam(defaultValue = "4000") int tokens, @RequestParam(required = false) String target,
            @RequestParam(required = false) String perspective) {

        var result = context.context(workspace, session, Bounds.contextTokens(tokens));
        return new Dtos.ContextResponse(result.summary(),
                result.messages().stream().map(Dtos.MessageResponse::of).toList(), result.messagesStartSeq(),
                result.summaryTokens(), result.messageTokens(), result.tokenBudget(),
                context.render(result, target == null ? session : target, perspective));
    }
}
