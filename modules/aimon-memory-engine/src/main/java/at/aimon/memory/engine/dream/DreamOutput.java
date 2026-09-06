package at.aimon.memory.engine.dream;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What a dream specialist returns.
 *
 * <p>{@code sourceIds} is required rather than optional. A derived conclusion whose premises are not
 * recorded cannot be audited, cannot be invalidated when a premise is deleted, and cannot be
 * explained to the person it is about — which for something the system decided on its own is not
 * acceptable.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DreamOutput(List<Item> conclusions) {

    public DreamOutput {
        conclusions = conclusions == null ? List.of() : List.copyOf(conclusions);
    }

    /**
     * {@code List.copyOf} rejects a null element here too, and for the reason worked out on
     * {@code DerivedConclusions.Item}: these lists are only ever filled by
     * {@code LlmClient.structured} under an explicit {@code ResponseFormat.strict(...)} whose array
     * elements are typed, and constrained decoding cannot emit {@code null} where a string is
     * required. {@code sourceIds} is the same shape as {@code entities} and inherits the same
     * argument. Kept in one piece there rather than repeated; revisit both together.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String content, List<String> sourceIds, List<String> entities, Double confidence) {
        public Item {
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
            entities = entities == null ? List.of() : List.copyOf(entities);
        }
    }

    public static DreamOutput empty() {
        return new DreamOutput(List.of());
    }
}
