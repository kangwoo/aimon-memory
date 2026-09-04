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
