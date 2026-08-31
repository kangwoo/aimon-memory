package dev.dyad.memory.derive;

import dev.dyad.core.key.PairKey;
import dev.dyad.core.model.Actor;
import dev.dyad.core.model.Message;
import dev.dyad.core.spi.LlmClient;
import dev.dyad.core.spi.llm.LlmRequest;
import dev.dyad.core.spi.llm.ResponseFormat;
import dev.dyad.core.spi.llm.StructuredResult;
import dev.dyad.memory.prompt.Prompts;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One model call per batch, producing conclusions and their entities together.
 *
 * <p>Fan-out happens after this, over storage: the same extraction is written to every observing
 * pair. Extracting per observer would multiply the cost of a group conversation by its size for no
 * gain — what differs between observers is the perspective, and that is decided when the batch is
 * routed, not by asking the model twice.
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
    private static final String SCHEMA =
            """
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
        LlmRequest request =
                new LlmRequest(
                        null,
                        Prompts.deriver(pair.observer(), pair.observed()),
                        List.of(dev.dyad.core.spi.llm.LlmMessage.user(MessageFormatter.format(messages))),
                        0.0,
                        null,
                        ResponseFormat.strict("derived_conclusions", SCHEMA));

        StructuredResult<DerivedConclusions> result = llm.structured(request, DerivedConclusions.class);
        log.debug(
                "derived {} conclusions for {} from {} messages",
                result.value().conclusions().size(),
                pair,
                messages.size());
        return result.value();
    }

    /** Extract once, then write the same result to every observing pair. */
    public ConclusionWriter.WriteResult deriveAndWrite(
            PairKey pair, String sessionName, List<Message> messages) {
        DerivedConclusions derived = derive(pair, messages);
        return write(pair, sessionName, derived, messages);
    }

    public ConclusionWriter.WriteResult write(
            PairKey pair, String sessionName, DerivedConclusions derived, List<Message> messages) {
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
