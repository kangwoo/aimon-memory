package at.aimon.memory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import at.aimon.memory.api.security.RoutePolicy;
import at.aimon.memory.testkit.golden.GoldenFixtures;

/**
 * The committed {@code docs/openapi.json} is what the running application generates.
 *
 * <p>A runtime endpoint alone would not have closed the gap it was added for. The reason this
 * repository has an API description at all is that a route could change shape — a field renamed, a
 * parameter made required — and nothing outside the Java source would say so. A document served on
 * demand still says nothing; a document in the tree makes the change a diff someone reviews, and this
 * test is what keeps the two the same.
 *
 * <p>Regenerate with the switch the golden fixtures use, since it is the same kind of file and one
 * switch is enough to learn:
 *
 * <pre>./gradlew :aimon-memory-api:integrationTest -Daimon.memory.golden.update=true</pre>
 */
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class OpenApiSpecTest extends ApiTestBase {

    /** HTTP methods as OpenAPI spells them, for walking the operations under a path. */
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");

    /**
     * Sorted keys and stable indentation, because this file is read as a diff.
     *
     * <p>Deserialising to {@code Object} rather than {@code JsonNode} is what makes the sorting apply:
     * the ordering feature acts on maps, and an {@code ObjectNode} is not one — it would have kept
     * whatever order the scan produced and turned every unrelated run into a diff.
     */
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.INDENT_OUTPUT, true);

    @Autowired
    private RoutePolicy policy;

    @Test
    void theCommittedDocumentMatchesWhatTheApplicationGenerates() throws Exception {
        String generated = canonical(fetch());
        Path file = documentPath();

        if (GoldenFixtures.updateMode()) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, generated, StandardCharsets.UTF_8);
            return;
        }

        assertThat(Files.exists(file)).as("missing %s; regenerate with -Daimon.memory.golden.update=true", file)
                .isTrue();
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .as("docs/openapi.json is stale: the API changed shape and the committed description did not."
                        + " Regenerate with -Daimon.memory.golden.update=true and read the diff")
                .isEqualTo(generated);
    }

    /**
     * Every route the authorisation table knows about is described, and described usefully.
     *
     * <p>The byte comparison above notices a document that fell behind. It cannot notice one that was
     * regenerated faithfully and says nothing — thirty-three operations with no summaries would match
     * themselves forever. This is the half that fails when a route is added without a word about it.
     */
    @Test
    void everyRouteIsDescribedWithASummaryAndItsScope() throws Exception {
        List<String> operations = new ArrayList<>();
        List<String> undescribed = new ArrayList<>();

        eachOperation((route, operation) -> {
            operations.add(route);
            if (operation.path("summary").asText("").isBlank()) {
                undescribed.add(route + " (no summary)");
            }
            if (!operation.path("description").asText("").contains("Requires a `")) {
                undescribed.add(route + " (no scope)");
            }
        });

        assertThat(undescribed).as("add @Operation(summary = ...), and an entry in RoutePolicy if it is missing one")
                .isEmpty();
        assertThat(operations).as("the document describes every route the authorisation table admits to")
                .hasSize(policy.size());
    }

    /**
     * The route strings in {@link OpenApiConfiguration}'s two error tables name routes that exist.
     *
     * <p>Sixteen hand-written paths decide which operations get a 422, a 503, a route-specific 409, or
     * no 409 at all, and both lookups miss silently. A typo there does not fail anything: the document
     * is regenerated, faithfully, slightly wrong, and the byte comparison above then holds it that way.
     * This is what {@code RoutePolicyCoverageTest} does for the authorisation table, for the same reason.
     *
     * <p>It does not check that a route in {@code READS_ONLY} truly writes nothing. That one is a
     * judgement about the handler, and it is written down where the set is.
     */
    @Test
    void theErrorTablesNameRoutesThatExist() throws Exception {
        List<String> live = new ArrayList<>();
        eachOperation((route, operation) -> live.add(route));

        assertThat(OpenApiConfiguration.routesNamedByTables())
                .as("a route named in ROUTE_ERRORS or READS_ONLY that no operation matches; the entry does"
                        + " nothing and nothing else would say so")
                .allSatisfy(named -> assertThat(live).contains(named));
    }

    /** Walks the generated document, handing each operation its {@code METHOD /path} name. */
    private void eachOperation(BiConsumer<String, JsonNode> visitor) throws Exception {
        JsonNode paths = new ObjectMapper().readTree(fetch()).get("paths");
        paths.properties().forEach(path -> path.getValue().properties().forEach(entry -> {
            if (METHODS.contains(entry.getKey())) {
                visitor.accept(entry.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey(), entry.getValue());
            }
        }));
    }

    /** Serve the document the way anything else would ask for it: no token, since it is not under /v1. */
    private String fetch() throws Exception {
        return mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static String canonical(String json) throws Exception {
        return CANONICAL.writeValueAsString(CANONICAL.readValue(json, Object.class)) + "\n";
    }

    private static Path documentPath() {
        String configured = System.getProperty("aimon.memory.docs.dir");
        Path docs = configured == null || configured.isBlank() ? Paths.get("docs") : Paths.get(configured);
        return docs.resolve("openapi.json");
    }
}
