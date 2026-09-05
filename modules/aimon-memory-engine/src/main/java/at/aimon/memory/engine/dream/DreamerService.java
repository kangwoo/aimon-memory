package at.aimon.memory.engine.dream;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.Conclusion;
import at.aimon.memory.core.model.ConclusionLevel;
import at.aimon.memory.core.spi.ConclusionStore;
import at.aimon.memory.core.spi.LlmClient;
import at.aimon.memory.core.spi.llm.LlmMessage;
import at.aimon.memory.core.spi.llm.LlmRequest;
import at.aimon.memory.core.spi.llm.ResponseFormat;
import at.aimon.memory.engine.derive.ConclusionWriter;
import at.aimon.memory.engine.prompt.Prompts;
import at.aimon.memory.store.repo.CollectionRepository;
import at.aimon.memory.store.repo.DreamRepository;

/**
 * The dreamer: deduction, induction and contradiction over what is already known.
 *
 * <p>Everything it writes goes through the same audit log as everything else. That is not
 * bookkeeping — this is the one component that changes memory with nobody watching, and without the
 * log there is no way to answer "where did this come from" about a belief no human ever stated.
 */
@Service
public class DreamerService {

    private static final Logger log = LoggerFactory.getLogger(DreamerService.class);

    /** Enough new material to be worth the call. Below this, a dream mostly re-derives itself. */
    public static final int EXPLICIT_THRESHOLD = 50;

    /** Floor between dreams for one pair, independent of how much arrived. */
    public static final Duration MIN_INTERVAL = Duration.ofHours(8);

    /** Working set per specialist. Beyond this the prompt stops fitting and quality falls off anyway. */
    private static final int MAX_WORKING_SET = 200;

    /** Patterns go stale. A deduction does not, so only induction gets a default lifetime. */
    private static final Duration INDUCTIVE_TTL = Duration.ofDays(365);

    private static final String SCHEMA = """
            {"type":"object","properties":{
              "conclusions":{"type":"array","items":{"type":"object","properties":{
                "content":{"type":"string","description":"The new conclusion, as a complete sentence."},
                "sourceIds":{"type":"array","items":{"type":"string"},
                             "description":"Ids of the given facts this rests on."},
                "entities":{"type":"array","items":{"type":"string"}},
                "confidence":{"type":"number","description":"0 to 1. Use 1 when it follows necessarily."}},
                "required":["content","sourceIds","entities","confidence"],
                "additionalProperties":false}}},
             "required":["conclusions"],"additionalProperties":false}
            """;

    private final LlmClient llm;
    private final ConclusionStore conclusions;
    private final ConclusionWriter writer;
    private final DreamRepository dreams;
    private final CollectionRepository collections;
    private final Clock clock;

    public DreamerService(LlmClient llm, ConclusionStore conclusions, ConclusionWriter writer, DreamRepository dreams,
            CollectionRepository collections, Clock clock) {
        this.llm = llm;
        this.conclusions = conclusions;
        this.writer = writer;
        this.dreams = dreams;
        this.collections = collections;
        this.clock = clock;
    }

    /**
     * Schedule a dream if the pair has earned one.
     *
     * <p>Two independent guards, plus the partial unique index underneath. Both are needed: the count
     * alone lets a busy pair dream continuously, and the interval alone lets a quiet pair dream about
     * three new facts.
     */
    public Optional<DreamRepository.Dream> scheduleIfDue(PairKey pair) {
        int explicitCount = conclusions.countByLevel(pair, ConclusionLevel.EXPLICIT);
        Optional<CollectionRepository.DreamState> state = collections.dreamState(pair);

        int atLastDream = state.map(CollectionRepository.DreamState::explicitAtLastDream).orElse(0);
        if (explicitCount - atLastDream < EXPLICIT_THRESHOLD) {
            return Optional.empty();
        }
        Instant lastDream = state.map(CollectionRepository.DreamState::lastDreamAt).orElse(null);
        if (lastDream != null && Duration.between(lastDream, clock.instant()).compareTo(MIN_INTERVAL) < 0) {
            return Optional.empty();
        }
        return dreams.schedule(pair, DreamRepository.DreamType.CONSOLIDATE, explicitCount);
    }

    /** Bypasses the thresholds but not the one-in-flight rule. */
    public Optional<DreamRepository.Dream> scheduleNow(PairKey pair, DreamRepository.DreamType type) {
        return dreams.schedule(pair, type, conclusions.countByLevel(pair, ConclusionLevel.EXPLICIT));
    }

    public void run(DreamRepository.Dream dream) {
        PairKey pair = new PairKey(dream.workspaceName(), dream.observer(), dream.observed());
        dreams.start(dream.id());
        try {
            List<Conclusion> working = conclusions.allForPair(pair, MAX_WORKING_SET);
            if (working.isEmpty()) {
                dreams.complete(dream.id(), 0);
                return;
            }
            String facts = renderFacts(working);
            Set<String> validIds = new HashSet<>();
            working.forEach(c -> validIds.add(c.id()));

            int produced = 0;
            produced += specialist(pair, dream.id(), facts, validIds, Prompts.DREAM_DEDUCTION,
                    ConclusionLevel.DEDUCTIVE);
            produced += specialist(pair, dream.id(), facts, validIds, Prompts.DREAM_INDUCTION,
                    ConclusionLevel.INDUCTIVE);
            produced += specialist(pair, dream.id(), facts, validIds, Prompts.DREAM_CONTRADICTION,
                    ConclusionLevel.CONTRADICTION);

            collections.markDreamed(pair, dream.conclusionsAtStart());
            dreams.complete(dream.id(), produced);
            log.info("dream {} for {} produced {} conclusions", dream.id(), pair, produced);
        } catch (RuntimeException e) {
            dreams.fail(dream.id(), e.getMessage());
            throw e;
        }
    }

    private int specialist(PairKey pair, String dreamId, String facts, Set<String> validIds, String prompt,
            ConclusionLevel level) {

        DreamOutput output = llm.structured(new LlmRequest(null, prompt, List.of(LlmMessage.user(facts)), 0.0, null,
                ResponseFormat.strict("dream_" + level.wire(), SCHEMA)), DreamOutput.class).value();

        List<ConclusionWriter.Incoming> items = new ArrayList<>();
        for (DreamOutput.Item item : output.conclusions()) {
            if (item.content() == null || item.content().isBlank()) {
                continue;
            }
            // Ids the model invented are dropped rather than stored. A dangling source id makes the
            // reasoning chain lie, and a lying provenance trail is worse than a missing one.
            List<String> sources = item.sourceIds().stream().filter(validIds::contains).toList();
            if (sources.isEmpty()) {
                log.debug("discarding {} conclusion with no valid premises: {}", level.wire(), item.content());
                continue;
            }
            items.add(new ConclusionWriter.Incoming(item.content(), item.entities(), level,
                    level == ConclusionLevel.INDUCTIVE ? confidence(item) : null, sources, List.of(),
                    level == ConclusionLevel.INDUCTIVE ? clock.instant().plus(INDUCTIVE_TTL) : null));
        }
        if (items.isEmpty()) {
            return 0;
        }
        writer.write(pair, null, items, Actor.DREAMER);
        return items.size();
    }

    private static double confidence(DreamOutput.Item item) {
        double raw = item.confidence() == null ? 0.5 : item.confidence();
        return Math.max(0.0, Math.min(1.0, raw));
    }

    /** Ids are shown so the model can cite them; nothing else in the row is useful to it. */
    private static String renderFacts(List<Conclusion> conclusions) {
        StringBuilder sb = new StringBuilder();
        for (Conclusion conclusion : conclusions) {
            sb.append('[').append(conclusion.id()).append("] ").append(conclusion.content()).append('\n');
        }
        return sb.toString();
    }
}
