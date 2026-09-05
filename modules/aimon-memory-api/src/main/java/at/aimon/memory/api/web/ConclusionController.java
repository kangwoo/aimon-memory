package at.aimon.memory.api.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import at.aimon.memory.api.Bounds;
import at.aimon.memory.api.dto.Dtos;
import at.aimon.memory.api.dto.Requests;
import at.aimon.memory.api.security.MemoryPrincipal;
import at.aimon.memory.api.security.PairScope;
import at.aimon.memory.core.NotFoundException;
import at.aimon.memory.core.filter.Filter;
import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.Conclusion;
import at.aimon.memory.core.model.EventType;
import at.aimon.memory.core.spi.ConclusionStore;
import at.aimon.memory.core.spi.EventLog;
import at.aimon.memory.engine.derive.ConclusionWriter;
import at.aimon.memory.engine.entity.EntityPipeline;
import at.aimon.memory.recall.ProvenanceService;
import at.aimon.memory.store.repo.PeerRepository;
import at.aimon.memory.store.repo.SessionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Direct conclusion access: list, inject, delete, audit trail, reasoning chain. */
@RestController
@Tag(name = "conclusions", description = "What is remembered: list, inject, delete, audit, reasoning chain.")
public class ConclusionController {

    private final ConclusionStore conclusions;
    private final PeerRepository peers;
    private final SessionRepository sessions;
    private final ConclusionWriter writer;
    private final EntityPipeline entities;
    private final EventLog events;
    private final ProvenanceService provenance;
    private final PairScope pairs;

    // Eight constructor-injected collaborators, one per repository this touches. Bundling them behind a
    // holder object to satisfy the count would hide which of them this class actually uses, which is the
    // thing the parameter list is good at saying.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ConclusionController(ConclusionStore conclusions, PeerRepository peers, SessionRepository sessions,
            ConclusionWriter writer, EntityPipeline entities, EventLog events, ProvenanceService provenance,
            PairScope pairs) {
        this.conclusions = conclusions;
        this.peers = peers;
        this.sessions = sessions;
        this.writer = writer;
        this.entities = entities;
        this.events = events;
        this.provenance = provenance;
        this.pairs = pairs;
    }

    @Operation(summary = "List a pair's conclusions")
    @GetMapping("/v1/workspaces/{workspace}/conclusions")
    public Dtos.PageResponse<Dtos.ConclusionResponse> list(@PathVariable String workspace, MemoryPrincipal principal,
            @RequestParam String observer, @RequestParam String observed, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        PairKey pair = pairs.of(principal, workspace, observer, observed);
        return Dtos.PageResponse.of(conclusions.list(pair, Filter.ALL, Bounds.page(page), Bounds.size(size)),
                Dtos.ConclusionResponse::of);
    }

    /**
     * Inject a fact directly.
     *
     * <p>Goes through the same dedup and the same audit log as anything the deriver produces — the
     * only difference is the actor recorded. A side door that skipped either would make the store's
     * invariants conditional on which path wrote the row.
     */
    @Operation(summary = "Inject a fact directly")
    @PostMapping("/v1/workspaces/{workspace}/conclusions")
    public Dtos.ConclusionResponse create(@PathVariable String workspace, MemoryPrincipal principal,
            @Valid @RequestBody Requests.CreateConclusion body) {
        PairKey pair = pairs.of(principal, workspace, body.observer(), body.observed());
        // Create the peers on demand, exactly as posting a message does. Requiring them to exist
        // first made this endpoint fail with a foreign-key violation for the ordinary case of
        // asserting a fact about someone the system has not been told about yet.
        peers.getOrCreate(workspace, body.observer(), Map.of(), Map.of());
        peers.getOrCreate(workspace, body.observed(), Map.of(), Map.of());
        if (body.session() != null) {
            sessions.getOrCreate(workspace, body.session(), Map.of(), Map.of());
        }
        var result = writer.write(pair, body.session(),
                List.of(new ConclusionWriter.Incoming(body.content(),
                        body.entities() == null ? List.of() : body.entities(),
                        at.aimon.memory.core.model.ConclusionLevel.EXPLICIT, null, List.of(), List.of(),
                        expiryOf(body.expiresAt()))),
                Actor.API);
        String id = result.outcomes().get(0).conclusionId();
        return conclusions.find(workspace, id).map(Dtos.ConclusionResponse::of)
                .orElseThrow(() -> new NotFoundException("conclusion", id));
    }

    /**
     * The caller's expiry, or a 400 saying why not.
     *
     * <p>{@link java.time.format.DateTimeParseException} has no entry in {@code ApiExceptionHandler},
     * so a bare {@code Instant.parse} here reached the catch-all: {@code "expiresAt": "2026-09-05"} —
     * a date with no time or offset, which is the first thing a caller writes — came back as 500 and
     * was logged at ERROR, putting a client's typo into the error-rate metric that is supposed to
     * reveal an outage. {@code ClientErrorStatusTest} exists for exactly that failure; this route was
     * the one path into it that the test did not cover.
     *
     * <p>Strict rather than lenient. An expiry is a deletion date, and guessing which midnight in
     * which zone {@code 2026-09-05} meant is not a guess to make silently on the caller's behalf.
     */
    private static Instant expiryOf(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (java.time.format.DateTimeParseException e) {
            throw new at.aimon.memory.core.MemoryException("bad_expiry",
                    "expiresAt must be an ISO-8601 instant such as 2026-09-05T12:00:00Z, got '" + raw + "'");
        }
    }

    /** Soft delete plus entity cleanup. The row stays; the audit log needs a subject to point at. */
    @Operation(summary = "Soft-delete a conclusion")
    @DeleteMapping("/v1/workspaces/{workspace}/conclusions/{id}")
    public Dtos.ConclusionResponse delete(@PathVariable String workspace, MemoryPrincipal principal,
            @PathVariable String id) {
        var conclusion = ownedConclusion(principal, workspace, id);
        conclusions.softDelete(workspace, id, Actor.API, Map.of("requested", true), EventType.DELETE);
        entities.unlink(workspace, id);
        return Dtos.ConclusionResponse.of(conclusion);
    }

    @Operation(summary = "Read a conclusion's audit trail")
    @GetMapping("/v1/workspaces/{workspace}/conclusions/{id}/events")
    public List<Dtos.EventResponse> history(@PathVariable String workspace, MemoryPrincipal principal,
            @PathVariable String id, @RequestParam(defaultValue = "100") int limit) {
        // Resolved before the log is read, for the ownership check below. It also means an id that
        // does not exist is a 404 rather than an empty array, which previously read as "this fact has
        // no history" — the one answer an audit endpoint must never give for something it cannot find.
        ownedConclusion(principal, workspace, id);
        return events.history(workspace, id, Bounds.history(limit)).stream().map(Dtos.EventResponse::of).toList();
    }

    /**
     * The conclusion behind an id, if it is in a pair this token owns.
     *
     * <p>These two routes name no pair — an id is the whole request — so {@link PairScope} has nothing
     * to check and the route table stops at "some peer token". That left any peer able to delete, or
     * read the audit trail of, any conclusion in its workspace by id. The pair comes off the row
     * instead, and is checked the same way: a pair is the observer's memory, and holding bob's token
     * is not a claim on alice's.
     *
     * <p>Not found rather than forbidden, matching the reasoning chain. Conclusion ids are opaque and
     * unguessable; an endpoint that distinguished "not yours" from "no such row" would turn that into
     * an oracle for enumerating another pair's rows without reading any of them.
     *
     * <p>Deliberately includes soft-deleted rows — {@code find} does not filter them — because reading
     * the audit trail of a fact that was just deleted is the main reason to read one at all.
     */
    private Conclusion ownedConclusion(MemoryPrincipal principal, String workspace, String id) {
        Conclusion conclusion = conclusions.find(workspace, id)
                .orElseThrow(() -> new NotFoundException("conclusion", id));
        if (!principal.canReachPeer(conclusion.pair().observer())) {
            throw new NotFoundException("conclusion", id);
        }
        return conclusion;
    }

    /** Both directions of the reasoning tree, plus the messages underneath. */
    @Operation(summary = "Walk a conclusion's reasoning chain")
    @GetMapping("/v1/workspaces/{workspace}/conclusions/{id}/chain")
    public Dtos.ProvenanceEntry chain(@PathVariable String workspace, MemoryPrincipal principal,
            @PathVariable String id, @RequestParam String observer, @RequestParam String observed) {
        PairKey pair = pairs.of(principal, workspace, observer, observed);
        var conclusion = conclusions.find(workspace, id).orElseThrow(() -> new NotFoundException("conclusion", id));
        // The id is looked up workspace-wide, so owning the named pair is not enough — the row has to
        // be in it. Not found rather than forbidden: whether a stranger's conclusion exists is itself
        // something this caller has no business learning.
        if (!conclusion.pair().equals(pair)) {
            throw new NotFoundException("conclusion", id);
        }
        var trace = provenance.forConclusion(pair, conclusion);
        return new Dtos.ProvenanceEntry(Dtos.ConclusionResponse.of(trace.conclusion()),
                trace.premises().stream().map(Dtos.ConclusionResponse::of).toList(),
                trace.sourceMessages().stream().map(Dtos.MessageResponse::of).toList(), trace.unresolvedPremiseIds());
    }
}
