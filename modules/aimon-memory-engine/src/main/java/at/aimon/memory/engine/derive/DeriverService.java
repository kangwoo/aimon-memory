package at.aimon.memory.engine.derive;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.Message;
import at.aimon.memory.core.spi.LlmClient;
import at.aimon.memory.core.spi.llm.LlmRequest;
import at.aimon.memory.core.spi.llm.ResponseFormat;
import at.aimon.memory.core.spi.llm.StructuredResult;
import at.aimon.memory.engine.prompt.Prompts;

/**
 * One model call per batch <em>per observing pair</em>, producing conclusions and their entities
 * together.
 *
 * <p>Per pair, not per batch, and the difference is worth being exact about because it is what a
 * group session costs. The system prompt carries the observer and the observed — "record only what
 * bob could reasonably conclude about alice" — so two pairs listening to the same messages are two
 * different questions with two different right answers. Sharing one extraction between them would
 * put alice's conclusions about herself into bob's memory, in her voice, with an audit trail saying
 * bob derived them.
 *
 * <p>The bill is therefore O(observing pairs) per batch, which for N mutually-observing peers in a
 * session is N + N(N−1). The specification assumed one call and N writes; ADR 0006 records why that
 * is not what happens and what the lever is ({@code observe_others}).
 *
 * <p>What batching does buy is unaffected: the token and idle-flush gates collapse a burst of
 * messages to one call per pair, so the multiplier is over observers rather than over traffic.
 */
@Service
public class DeriverService {

    private static final Logger log = LoggerFactory.getLogger(DeriverService.class);

    /**
     * Hand-written rather than reflected from the record.
     *
     * <p>The descriptions here are part of the prompt in practice — a model reads them — and a
     * generator cannot produce them. It is also the schema the fixture key hashes, so it should change
     * only when someone means to change it.
     */
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "conclusions": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "content": {
                        "type": "string",
                        "description": "One durable fact, as a complete self-contained sentence."
                      },
                      "entities": {
                        "type": "array",
                        "items": { "type": "string" },
                        "description": "Named entities mentioned, in their surface form."
                      }
                    },
                    "required": ["content", "entities"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["conclusions"],
              "additionalProperties": false
            }
            """;

    private final LlmClient llm;
    private final ConclusionWriter writer;

    public DeriverService(LlmClient llm, ConclusionWriter writer) {
        this.llm = llm;
        this.writer = writer;
    }

    public DerivedConclusions derive(PairKey pair, List<Message> messages) {
        if (messages.isEmpty()) {
            return DerivedConclusions.empty();
        }
        LlmRequest request = new LlmRequest(null, Prompts.deriver(pair.observer(), pair.observed()),
                List.of(at.aimon.memory.core.spi.llm.LlmMessage.user(MessageFormatter.format(messages))), 0.0, null,
                ResponseFormat.strict("derived_conclusions", SCHEMA));

        StructuredResult<DerivedConclusions> result = llm.structured(request, DerivedConclusions.class);
        log.debug("derived {} conclusions for {} from {} messages", result.value().conclusions().size(), pair,
                messages.size());
        return result.value();
    }

    /** Extract from this pair's point of view, then write the result to that pair. */
    public ConclusionWriter.WriteResult deriveAndWrite(PairKey pair, String sessionName, List<Message> messages) {
        DerivedConclusions derived = derive(pair, messages);
        return write(pair, sessionName, derived, messages);
    }

    public ConclusionWriter.WriteResult write(PairKey pair, String sessionName, DerivedConclusions derived,
            List<Message> messages) {
        List<Long> messageIds = messages.stream().map(Message::id).toList();
        List<ConclusionWriter.Incoming> items = new ArrayList<>(derived.conclusions().size());
        for (DerivedConclusions.Item item : derived.conclusions()) {
            if (item.content() == null || item.content().isBlank()) {
                continue;
            }
            items.add(ConclusionWriter.Incoming.explicit(item.content(), item.entities(), messageIds));
        }
        return writer.write(pair, sessionName, items, Actor.DERIVER);
    }
}
