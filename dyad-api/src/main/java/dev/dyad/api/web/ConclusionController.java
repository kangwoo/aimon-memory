package dev.dyad.api.web;

import dev.dyad.api.Bounds;
import dev.dyad.api.dto.Dtos;
import dev.dyad.api.dto.Requests;
import dev.dyad.core.NotFoundException;
import dev.dyad.core.filter.Filter;
import dev.dyad.core.key.PairKey;
import dev.dyad.core.model.Actor;
import dev.dyad.core.model.EventType;
import dev.dyad.memory.derive.ConclusionWriter;
import dev.dyad.memory.entity.EntityPipeline;
import dev.dyad.recall.ProvenanceService;
import dev.dyad.store.repo.ConclusionRepository;
import dev.dyad.store.repo.EventLogRepository;
import dev.dyad.store.repo.PeerRepository;
import dev.dyad.store.repo.SessionRepository;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Direct conclusion access: list, inject, delete, audit trail, reasoning chain. */
@RestController
public class ConclusionController {

    private final ConclusionRepository conclusions;
    private final PeerRepository peers;
    private final SessionRepository sessions;
    private final ConclusionWriter writer;
    private final EntityPipeline entities;
    private final EventLogRepository events;
    private final ProvenanceService provenance;

    public ConclusionController(
            ConclusionRepository conclusions,
            PeerRepository peers,
            SessionRepository sessions,
            ConclusionWriter writer,
            EntityPipeline entities,
            EventLogRepository events,
            ProvenanceService provenance) {
        this.conclusions = conclusions;
        this.peers = peers;
        this.sessions = sessions;
        this.writer = writer;
        this.entities = entities;
        this.events = events;
        this.provenance = provenance;
    }

    @GetMapping("/v1/workspaces/{workspace}/conclusions")
    public Dtos.PageResponse<Dtos.ConclusionResponse> list(
            @PathVariable String workspace,
            @RequestParam String observer,
            @RequestParam String observed,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        PairKey pair = new PairKey(workspace, observer, observed);
        return Dtos.PageResponse.of(
                conclusions.list(pair, Filter.ALL, Bounds.page(page), Bounds.size(size)),
                Dtos.ConclusionResponse::of);
    }

    /**
     * Inject a fact directly.
     *
     * <p>Goes through the same dedup and the same audit log as anything the deriver produces — the
     * only difference is the actor recorded. A side door that skipped either would make the store's
     * invariants conditional on which path wrote the row.
     */
    @PostMapping("/v1/workspaces/{workspace}/conclusions")
    public Dtos.ConclusionResponse create(
            @PathVariable String workspace, @Valid @RequestBody Requests.CreateConclusion body) {
        PairKey pair = new PairKey(workspace, body.observer(), body.observed());
        // Create the peers on demand, exactly as posting a message does. Requiring them to exist
        // first made this endpoint fail with a foreign-key violation for the ordinary case of
        // asserting a fact about someone the system has not been told about yet.
        peers.getOrCreate(workspace, body.observer(), Map.of(), Map.of());
        peers.getOrCreate(workspace, body.observed(), Map.of(), Map.of());
        if (body.session() != null) {
            sessions.getOrCreate(workspace, body.session(), Map.of(), Map.of());
        }
        var result =
                writer.write(
                        pair,
                        body.session(),
                        List.of(
                                new ConclusionWriter.Incoming(
                                        body.content(),
                                        body.entities() == null ? List.of() : body.entities(),
                                        dev.dyad.core.model.ConclusionLevel.EXPLICIT,
                                        null,
                                        List.of(),
                                        List.of(),
                                        body.expiresAt() == null ? null : Instant.parse(body.expiresAt()))),
                        Actor.API);
        String id = result.outcomes().get(0).conclusionId();
        return conclusions
                .find(workspace, id)
                .map(Dtos.ConclusionResponse::of)
                .orElseThrow(() -> new NotFoundException("conclusion", id));
    }

    /** Soft delete plus entity cleanup. The row stays; the audit log needs a subject to point at. */
    @DeleteMapping("/v1/workspaces/{workspace}/conclusions/{id}")
    public Dtos.ConclusionResponse delete(@PathVariable String workspace, @PathVariable String id) {
        var conclusion =
                conclusions.find(workspace, id).orElseThrow(() -> new NotFoundException("conclusion", id));
        conclusions.softDelete(workspace, id, Actor.API, Map.of("requested", true), EventType.DELETE);
        entities.unlink(workspace, id);
        return Dtos.ConclusionResponse.of(conclusion);
    }

    @GetMapping("/v1/workspaces/{workspace}/conclusions/{id}/events")
    public List<Dtos.EventResponse> history(
            @PathVariable String workspace,
            @PathVariable String id,
            @RequestParam(defaultValue = "100") int limit) {
        return events.history(workspace, id, Bounds.history(limit)).stream()
                .map(Dtos.EventResponse::of)
                .toList();
    }

    /** Both directions of the reasoning tree, plus the messages underneath. */
    @GetMapping("/v1/workspaces/{workspace}/conclusions/{id}/chain")
    public Dtos.ProvenanceEntry chain(
            @PathVariable String workspace,
            @PathVariable String id,
            @RequestParam String observer,
            @RequestParam String observed) {
        var conclusion =
                conclusions.find(workspace, id).orElseThrow(() -> new NotFoundException("conclusion", id));
        var trace = provenance.forConclusion(new PairKey(workspace, observer, observed), conclusion);
        return new Dtos.ProvenanceEntry(
                Dtos.ConclusionResponse.of(trace.conclusion()),
                trace.premises().stream().map(Dtos.ConclusionResponse::of).toList(),
                trace.sourceMessages().stream().map(Dtos.MessageResponse::of).toList(),
                trace.unresolvedPremiseIds());
    }
}
