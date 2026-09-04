package at.aimon.memory.api.web;

import java.util.List;
import java.util.Map;

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
import at.aimon.memory.core.ConflictException;
import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.key.WorkUnitKey;
import at.aimon.memory.engine.dream.DreamerService;
import at.aimon.memory.engine.dream.PeerCardService;
import at.aimon.memory.store.repo.DreamRepository;
import at.aimon.memory.store.repo.QueueRepository;

/** Dream scheduling and the peer card. */
@RestController
public class DreamController {

    private final DreamerService dreamer;
    private final DreamRepository dreams;
    private final PeerCardService cards;
    private final QueueRepository queue;
    private final PairScope pairs;

    public DreamController(DreamerService dreamer, DreamRepository dreams, PeerCardService cards, QueueRepository queue,
            PairScope pairs) {
        this.dreamer = dreamer;
        this.dreams = dreams;
        this.cards = cards;
        this.queue = queue;
        this.pairs = pairs;
    }

    /**
     * Schedule a dream now, bypassing the thresholds but not the one-in-flight rule.
     *
     * <p>Returns 409 when one is already running for the pair. The database decides that, via the
     * partial unique index — this endpoint and the automatic scheduler race constantly, and the
     * loser has to find out from a constraint rather than from a prior read.
     */
    @PostMapping("/v1/workspaces/{workspace}/dreams")
    public Dtos.DreamResponse schedule(@PathVariable String workspace, MemoryPrincipal principal,
            @Valid @RequestBody Requests.ScheduleDream body) {
        PairKey pair = pairs.of(principal, workspace, body.observer(), body.observed());
        DreamRepository.DreamType type = "card_refresh".equalsIgnoreCase(body.type())
                ? DreamRepository.DreamType.CARD_REFRESH
                : DreamRepository.DreamType.CONSOLIDATE;

        DreamRepository.Dream dream = dreamer.scheduleNow(pair, type)
                .orElseThrow(() -> new ConflictException("a dream is already in flight for " + pair));

        queue.enqueue(type == DreamRepository.DreamType.CARD_REFRESH
                ? WorkUnitKey.cardRefresh(pair)
                : WorkUnitKey.dream(pair), Map.of("dream_id", dream.id()), 0);
        return toResponse(dream);
    }

    @GetMapping("/v1/workspaces/{workspace}/dreams")
    public List<Dtos.DreamResponse> list(@PathVariable String workspace, MemoryPrincipal principal,
            @RequestParam String observer, @RequestParam String observed,
            @RequestParam(defaultValue = "20") int limit) {
        PairKey pair = pairs.of(principal, workspace, observer, observed);
        return dreams.forPair(pair, Bounds.history(limit)).stream().map(DreamController::toResponse).toList();
    }

    @GetMapping("/v1/workspaces/{workspace}/peer-card")
    public Dtos.PeerCardResponse card(@PathVariable String workspace, MemoryPrincipal principal,
            @RequestParam String observer, @RequestParam String observed) {
        PairKey pair = pairs.of(principal, workspace, observer, observed);
        return cards.find(pair)
                .map(card -> new Dtos.PeerCardResponse(observer, observed, card.lines(), card.updatedAt()))
                .orElse(new Dtos.PeerCardResponse(observer, observed, List.of(), null));
    }

    /**
     * Regenerate the card synchronously.
     *
     * <p>One cheap model call, no tools, and it does not advance the dreamer's counters — refreshing a
     * card must not consume the budget meant for the pass that produces new knowledge.
     */
    @PostMapping("/v1/workspaces/{workspace}/peer-card/refresh")
    public Dtos.PeerCardResponse refresh(@PathVariable String workspace, MemoryPrincipal principal,
            @RequestParam String observer, @RequestParam String observed) {
        PairKey pair = pairs.of(principal, workspace, observer, observed);
        List<String> lines = cards.refresh(pair);
        return new Dtos.PeerCardResponse(observer, observed, lines, java.time.Instant.now());
    }

    private static Dtos.DreamResponse toResponse(DreamRepository.Dream dream) {
        return new Dtos.DreamResponse(dream.id(), dream.observer(), dream.observed(), dream.dreamType(), dream.status(),
                dream.produced(), dream.error(), dream.createdAt(), dream.completedAt());
    }
}
