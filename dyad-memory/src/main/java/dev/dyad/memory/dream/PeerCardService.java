package dev.dyad.memory.dream;

import dev.dyad.core.DyadException;
import dev.dyad.core.key.PairKey;
import dev.dyad.core.model.Conclusion;
import dev.dyad.core.spi.LlmClient;
import dev.dyad.core.spi.llm.LlmMessage;
import dev.dyad.core.spi.llm.LlmRequest;
import dev.dyad.core.spi.llm.ResponseFormat;
import dev.dyad.memory.prompt.Prompts;
import dev.dyad.store.repo.ConclusionRepository;
import dev.dyad.store.repo.PeerCardRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The peer card: a compact, wholly-replaced profile of one peer as seen by another.
 *
 * <p>Cheap on purpose. It reads conclusions and writes lines, and it touches nothing else — no tools,
 * no new conclusions, and it does not advance the dreamer's scheduling counters. Refreshing a card
 * must not consume the budget for the reasoning pass that produces new knowledge.
 *
 * <p>Lines must carry one of four prefixes. The constraint is doing real work: it forces the model to
 * decide what kind of thing each line is, and it makes the card mechanically checkable, so a
 * malformed generation is dropped rather than stored and read as fact later.
 */
@Service
public class PeerCardService {

    public static final Set<String> PREFIXES =
            Set.of("IDENTITY:", "ATTRIBUTE:", "RELATIONSHIP:", "INSTRUCTION:");
    public static final int MAX_LINES = 40;

    private static final int WORKING_SET = 150;
    private static final String SCHEMA =
            """
            {"type":"object","properties":{
              "lines":{"type":"array","items":{"type":"string"}}},
             "required":["lines"],"additionalProperties":false}
            """;

    private final LlmClient llm;
    private final ConclusionRepository conclusions;
    private final PeerCardRepository cards;

    public PeerCardService(LlmClient llm, ConclusionRepository conclusions, PeerCardRepository cards) {
        this.llm = llm;
        this.conclusions = conclusions;
        this.cards = cards;
    }

    public record CardPayload(List<String> lines) {
        public CardPayload {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public Optional<PeerCardRepository.PeerCard> find(PairKey pair) {
        return cards.find(pair);
    }

    public List<String> refresh(PairKey pair) {
        List<Conclusion> working = conclusions.allForPair(pair, WORKING_SET);
        if (working.isEmpty()) {
            return List.of();
        }
        StringBuilder facts = new StringBuilder();
        working.forEach(c -> facts.append("- ").append(c.content()).append('\n'));

        CardPayload payload =
                llm.structured(
                                new LlmRequest(
                                        null,
                                        Prompts.PEER_CARD,
                                        List.of(LlmMessage.user(facts.toString())),
                                        0.0,
                                        null,
                                        ResponseFormat.strict("peer_card", SCHEMA)),
                                CardPayload.class)
                        .value();

        List<String> valid = validate(payload.lines());
        if (valid.isEmpty()) {
            // Whole-replacement only works when the generation succeeded. There are facts to describe,
            // so an empty card means the model ignored the prefix format — and writing it would
            // destroy a good card because one generation came back malformed.
            throw new DyadException(
                    "card_generation_failed",
                    "peer card generation produced no usable lines out of "
                            + payload.lines().size()
                            + "; the previous card is unchanged");
        }
        cards.replace(pair, valid);
        return valid;
    }

    /** Drop anything that does not carry a known prefix, then cap. Silent truncation, loud rejection. */
    static List<String> validate(List<String> lines) {
        List<String> valid = new ArrayList<>();
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            boolean prefixed = PREFIXES.stream().anyMatch(trimmed::startsWith);
            if (prefixed) {
                valid.add(trimmed);
            }
            if (valid.size() == MAX_LINES) {
                break;
            }
        }
        return List.copyOf(valid);
    }
}
