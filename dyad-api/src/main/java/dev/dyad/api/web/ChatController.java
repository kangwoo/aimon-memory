package dev.dyad.api.web;

import dev.dyad.api.dto.Dtos;
import dev.dyad.api.dto.Requests;
import dev.dyad.api.security.DyadPrincipal;
import dev.dyad.api.security.ForbiddenException;
import dev.dyad.api.security.PairScope;
import dev.dyad.core.key.PairKey;
import dev.dyad.core.spi.llm.LlmMessage;
import dev.dyad.core.spi.llm.Role;
import dev.dyad.memory.dialectic.DialecticService;
import dev.dyad.memory.dialectic.ReasoningLevel;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Tier 2: the agentic path, blocking and streaming. */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final long SSE_TIMEOUT_MILLIS = 300_000;

    private final DialecticService dialectic;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final PairScope pairs;

    public ChatController(
            DialecticService dialectic,
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            PairScope pairs) {
        this.dialectic = dialectic;
        this.mapper = mapper;
        this.pairs = pairs;
    }

    @PostMapping("/v1/workspaces/{workspace}/chat")
    public Dtos.ChatResponse chat(
            @PathVariable String workspace,
            DyadPrincipal principal,
            @Valid @RequestBody Requests.ChatRequest body) {

        var result = dialectic.answer(question(workspace, principal, body));
        return new Dtos.ChatResponse(
                result.text(),
                result.iterations(),
                result.stoppedAtLimit(),
                result.calls().stream()
                        .map(c -> new Dtos.ToolCallResponse(c.name(), c.argumentsJson(), c.failed()))
                        .toList());
    }

    /**
     * Streaming answer over SSE.
     *
     * <p>The emitter runs on a virtual thread. A dialectic answer can take tens of seconds of mostly
     * waiting, and parking a virtual thread on that wait costs a few hundred bytes rather than a
     * platform thread — which is the entire reason this is MVC and not a reactive stack.
     */
    @PostMapping(value = "/v1/workspaces/{workspace}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable String workspace,
            DyadPrincipal principal,
            @Valid @RequestBody Requests.ChatRequest body) {

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        DialecticService.Question question = question(workspace, principal, body);
        AtomicBoolean live = new AtomicBoolean(true);
        // Published before the worker starts and read by the timeout callback afterwards, so the
        // callback can be registered once — see the note where it is installed.
        AtomicReference<Thread> worker = new AtomicReference<>();

        // A dialectic answer can run for tens of seconds and several tool calls. Without these, a
        // client that closes the tab leaves the loop running to completion — still calling the model,
        // still querying — to send its answer to a socket nobody is reading.
        //
        // One onTimeout callback, not two. ResponseBodyEmitter keeps a single delegate, so registering
        // a second one silently discarded the first: only the interrupt fired, and a worker that was
        // already past the provider call and inside the send loop never saw it — `live` stayed true,
        // and every send after the timeout threw into a socket nobody was reading.
        emitter.onTimeout(
                () -> {
                    live.set(false);
                    Thread running = worker.get();
                    if (running != null) {
                        running.interrupt();
                    }
                });
        emitter.onCompletion(() -> live.set(false));
        emitter.onError(e -> live.set(false));

        worker.set(
                Thread.ofVirtual()
                        .name("dyad-sse")
                        .start(
                                () -> {
                                    try {
                                        dialectic
                                                .answerStreaming(question)
                                                .takeWhile(chunk -> live.get())
                                                .forEach(
                                                        chunk -> {
                                                            try {
                                                                emitter.send(
                                                                        SseEmitter.event().name("delta").data(chunk));
                                                            } catch (IOException e) {
                                                                live.set(false);
                                                                throw new IllegalStateException(e);
                                                            }
                                                        });
                                        if (live.get()) {
                                            emitter.send(SseEmitter.event().name("done").data(""));
                                            emitter.complete();
                                        }
                                    } catch (RuntimeException | IOException e) {
                                        if (live.get()) {
                                            log.warn("chat stream failed: {}", e.getMessage());
                                            emitter.completeWithError(e);
                                        }
                                    }
                                }));
        return emitter;
    }

    private DialecticService.Question question(
            String workspace, DyadPrincipal principal, Requests.ChatRequest body) {
        PairKey pair = pairs.of(principal, workspace, body.observer(), body.observed());
        // A session-scoped token may only ask about its own session. Checked here rather than only in
        // the route table, because the session arrives in the body, not the path.
        if (body.session() != null && !principal.canReachSession(body.session())) {
            throw new ForbiddenException("token is not scoped to session " + body.session());
        }
        List<LlmMessage> history =
                body.history() == null
                        ? List.of()
                        : body.history().stream()
                                .map(t -> new LlmMessage(
                                        "assistant".equalsIgnoreCase(t.role()) ? Role.ASSISTANT : Role.USER,
                                        t.content()))
                                .toList();
        String schema = null;
        if (body.responseFormat() != null && !body.responseFormat().isEmpty()) {
            try {
                schema = mapper.writeValueAsString(body.responseFormat());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new dev.dyad.core.DyadException("bad_response_format", "response_format is not serialisable");
            }
        }
        return new DialecticService.Question(
                pair, body.session(), body.question(), history,
                ReasoningLevel.fromWire(body.reasoningLevel()), schema);
    }
}
