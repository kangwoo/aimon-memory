package at.aimon.memory.api.web;

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
import at.aimon.memory.api.security.MemoryPrincipal;
import at.aimon.memory.api.security.PairScope;
import at.aimon.memory.core.NotFoundException;
import at.aimon.memory.core.filter.Filter;
import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.recall.ProvenanceService;
import at.aimon.memory.recall.RecallRequest;
import at.aimon.memory.recall.RecallService;

/** Tier 1 recall and entity-anchored provenance. */
@RestController
public class RecallController {

    private final RecallService recall;
    private final ProvenanceService provenance;
    private final PairScope pairs;

    public RecallController(RecallService recall, ProvenanceService provenance, PairScope pairs) {
        this.recall = recall;
        this.provenance = provenance;
        this.pairs = pairs;
    }

    @PostMapping("/v1/workspaces/{workspace}/recall")
    public Dtos.RecallResponseBody recall(@PathVariable String workspace, MemoryPrincipal principal,
            @Valid @RequestBody Requests.RecallQuery body) {
        PairKey pair = pairs.of(principal, workspace, body.observer(), body.observed());
        var response = recall.recall(new RecallRequest(pair, body.query(), Bounds.recallLimit(body.limit()),
                Filter.parse(body.filter()), body.threshold(), body.explain() == null || body.explain()));
        return new Dtos.RecallResponseBody(response.hits().stream().map(Dtos.RecallHitResponse::of).toList(),
                response.analyzedQuery(), response.candidatesConsidered());
    }

    /**
     * From a name to the sentences behind every belief that mentions it. No model calls.
     *
     * <p>This is the capability neither source design has: one can find the conclusions but not their
     * evidence, the other has the evidence chain but nothing to look it up by.
     */
    @GetMapping("/v1/workspaces/{workspace}/recall/provenance")
    public Dtos.ProvenanceResponse provenance(@PathVariable String workspace, MemoryPrincipal principal,
            @RequestParam String entity, @RequestParam String observer, @RequestParam String observed,
            @RequestParam(defaultValue = "10") int limit) {

        PairKey pair = pairs.of(principal, workspace, observer, observed);
        return provenance.forEntity(pair, entity, Bounds.recallLimit(limit))
                .map(result -> new Dtos.ProvenanceResponse(result.entity().nameDisplay(),
                        result.conclusions().stream()
                                .map(entry -> new Dtos.ProvenanceEntry(Dtos.ConclusionResponse.of(entry.conclusion()),
                                        entry.premises().stream().map(Dtos.ConclusionResponse::of).toList(),
                                        entry.sourceMessages().stream().map(Dtos.MessageResponse::of).toList(),
                                        entry.unresolvedPremiseIds()))
                                .toList()))
                .orElseThrow(() -> new NotFoundException("entity", entity));
    }
}
