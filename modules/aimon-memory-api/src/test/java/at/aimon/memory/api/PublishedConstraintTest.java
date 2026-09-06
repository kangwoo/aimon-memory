package at.aimon.memory.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.memory.api.dto.Requests;

/**
 * The lower bounds {@code docs/openapi.json} publishes are the ones the runtime enforces.
 *
 * <p>swagger-core reads {@code @NotEmpty} and {@code @NotBlank} first, setting {@code minItems: 1} or
 * {@code minLength: 1}, and then reads {@code @Size} over the top —
 * {@code ValidationAnnotationsUtils.applySizeConstraint} calls {@code setMinItems(min)} and
 * {@code setMinLength(min)} with no guard on whether anything already set them. So adding a ceiling to
 * a field that had a floor silently removes the floor from the published description while the runtime
 * goes on enforcing it, and the document starts telling generated clients that an empty array or an
 * empty string is a valid value. It has happened twice: once to {@code NewMessage.content}, caught
 * when {@code minLength: 0} appeared in a diff, and once to {@code CreateMessages.messages}, which
 * published {@code minItems: 0} against a {@code @NotEmpty} that had been answering 400 since the
 * field was written.
 *
 * <p>{@code OpenApiSpecTest} cannot catch this. It proves the committed document is what the
 * application generates, and the application generates the wrong thing faithfully. This is the half
 * that reads what the document actually says.
 *
 * <p>Two tests rather than one, because they fail in different places for the same cause: the first
 * names the field and the annotation to fix, the second is the invariant a reviewer can check by
 * eye and holds no matter which library produced the file. The second is also the wider of the two —
 * the first reads field annotations and so cannot see a constraint on a list's <em>elements</em>, which
 * is a shape this file already has in {@code CreateConclusion.entities}. Verified by writing
 * {@code List<@NotBlank @Size(max = 10) String>} there: the reflection walk passed it and the document
 * walk caught {@code components.schemas.CreateConclusion.properties.entities.items.minLength}.
 */
class PublishedConstraintTest {

    private static final Class<?>[] REQUEST_RECORDS = Requests.class.getDeclaredClasses();

    /**
     * Every {@code @Size} <em>on a field</em> that sits beside a not-empty constraint states its own
     * minimum.
     *
     * <p>Reflecting over the fields rather than the record components on purpose: Bean Validation's
     * annotations do not target {@code RECORD_COMPONENT}, so the compiler propagates them to the
     * backing field and {@code RecordComponent.getAnnotations()} comes back empty.
     *
     * <p>Which is also the limit of what this half sees. A container-element constraint —
     * {@code List<@NotBlank @Size(max = …) String>} — is a {@code TYPE_USE} annotation and is not on the
     * field, so it does not appear here. It publishes the same {@code minLength: 0}, one level down
     * under {@code items}, and the document walk below is what catches it.
     */
    @Test
    void aSizeBesideANotEmptyOrNotBlankStatesItsOwnMinimum() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> record : REQUEST_RECORDS) {
            if (!record.isRecord()) {
                continue;
            }
            for (Field field : record.getDeclaredFields()) {
                Size size = field.getAnnotation(Size.class);
                boolean hasFloor = field.isAnnotationPresent(NotEmpty.class)
                        || field.isAnnotationPresent(NotBlank.class);
                if (size != null && hasFloor && size.min() < 1) {
                    offenders.add(record.getSimpleName() + "." + field.getName());
                }
            }
        }

        assertThat(offenders).as("@Size(max = ...) beside @NotEmpty/@NotBlank publishes minItems/minLength 0 and"
                + " erases the floor the runtime still enforces; write min = 1 as well").isEmpty();
        // The walk found something, so an empty result means the constraints hold rather than that
        // reflection quietly returned nothing.
        assertThat(sizedFields()).as("no field carries @Size at all; the check above proved nothing").isNotEmpty();
    }

    /** The same rule read off the published file, where a consumer would read it. */
    @Test
    void theDocumentPublishesNoZeroLowerBound() {
        JsonNode schemas = document().path("components").path("schemas");
        List<String> zeroes = new ArrayList<>();
        collectZeroLowerBounds(schemas, "components.schemas", zeroes);

        assertThat(schemas).as("no component schemas in docs/openapi.json").isNotEmpty();
        assertThat(zeroes).as("minItems/minLength 0 says nothing a consumer did not already assume, and in this"
                + " repository it has only ever meant a @Size overwrote a @NotEmpty or @NotBlank").isEmpty();
    }

    private static void collectZeroLowerBounds(JsonNode node, String path, List<String> found) {
        if (node.isObject()) {
            for (String bound : List.of("minItems", "minLength", "minProperties")) {
                if (node.path(bound).isInt() && node.path(bound).asInt() == 0) {
                    found.add(path + "." + bound);
                }
            }
            node.properties()
                    .forEach(entry -> collectZeroLowerBounds(entry.getValue(), path + "." + entry.getKey(), found));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectZeroLowerBounds(node.get(i), path + "[" + i + "]", found);
            }
        }
    }

    private static List<String> sizedFields() {
        List<String> sized = new ArrayList<>();
        for (Class<?> record : REQUEST_RECORDS) {
            for (Field field : record.getDeclaredFields()) {
                if (field.isAnnotationPresent(Size.class)) {
                    sized.add(record.getSimpleName() + "." + field.getName());
                }
            }
        }
        return sized;
    }

    private static JsonNode document() {
        String configured = System.getProperty("aimon.memory.docs.dir");
        Path docs = configured == null || configured.isBlank() ? Paths.get("docs") : Paths.get(configured);
        Path file = docs.resolve("openapi.json");
        try {
            return new ObjectMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }
}
