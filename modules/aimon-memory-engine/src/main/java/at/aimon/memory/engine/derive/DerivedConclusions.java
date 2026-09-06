package at.aimon.memory.engine.derive;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The extraction schema.
 *
 * <p>{@code entities} is the whole trick: entity extraction is a field on a call that was already
 * happening, not a second pass. It costs nothing, and unlike the capitalisation-and-quotes heuristic
 * it replaces, it works on Korean — where there is no capitalisation to key off at all.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DerivedConclusions(List<Item> conclusions) {

    public DerivedConclusions {
        conclusions = conclusions == null ? List.of() : List.copyOf(conclusions);
    }

    /**
     * {@code List.copyOf} rejects a null element, and that is left as it is on purpose.
     *
     * <p>The question this answers — asked once in review and re-opened once since — is whether a
     * model can hand back {@code [null]} inside one of these arrays. It would throw an NPE out of the
     * compact constructor, fail the work unit, and quarantine it after {@code max-attempts} (5), which
     * is an expensive way to learn that a provider broke its own contract. So: can it?
     *
     * <p>Not through either provider this build implements. Both of these records are only ever
     * produced by {@code LlmClient.structured}, and every call site hands it an explicit
     * {@code ResponseFormat.strict(...)} with a hand-written schema in which the array elements are
     * typed ({@code items: {"type": "string"}} for the entity lists, a required-and-closed object for
     * the conclusion lists) — four call sites, counted. A fifth that passed none would not weaken the
     * argument either: {@code DefaultLlmClient.structured} derives a format from the record itself
     * through {@code JsonSchemas} when the request carries none, and that one is strict by
     * construction. {@code OpenAiChatBackend} sends the format as {@code response_format.json_schema}
     * with {@code strict: true}; {@code AnthropicChatBackend} sends it as {@code output_config.format}
     * with {@code type: json_schema}, which is constrained rather than best-effort and so has no strict
     * flag to forward. Under constrained decoding {@code null} is not a producible value where a string
     * or an object is required.
     *
     * <p>The near miss is truncation, not nulls: a response cut off at {@code max_tokens} is documented
     * on {@code AnthropicChatBackend} as half a JSON document, and that fails in {@code Json.read} as a
     * parse error before any constructor runs.
     *
     * <p>What would make it reachable is pointing {@code aimon.memory.llm.open-ai-base-url} or
     * {@code .anthropic-base-url} at an OpenAI-compatible gateway that accepts {@code strict} and does
     * not enforce it. Those properties bind but are absent from both {@code application.yml} files, so
     * it takes a deliberately non-default deployment. If that ever becomes a supported configuration,
     * this is the constructor to revisit — and the fix is a null-tolerant copy here, not a schema
     * change, because the schema is already right.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String content, List<String> entities) {
        public Item {
            entities = entities == null ? List.of() : List.copyOf(entities);
        }
    }

    public static DerivedConclusions empty() {
        return new DerivedConclusions(List.of());
    }
}
