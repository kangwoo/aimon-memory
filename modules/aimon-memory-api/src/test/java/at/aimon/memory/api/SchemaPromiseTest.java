package at.aimon.memory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.memory.api.dto.Requests;

/**
 * The bounds {@code docs/openapi.json} publishes are bounds the running service keeps.
 *
 * <p>{@code OpenApiSpecTest} compares the committed file against the one the application generates,
 * and {@code PublishedConstraintTest} reads what that file says. Neither sends a request. Both of the
 * defects this class was written for lived in the space between: a document that was internally
 * consistent, faithfully regenerated, and describing behaviour the service did not have.
 *
 * <p>Every payload here is sized from the published document rather than from a constant, so the
 * assertion is about the two agreeing rather than about a number appearing twice.
 */
class SchemaPromiseTest extends ApiTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String token;

    @BeforeEach
    void seed() throws Exception {
        token = adminToken();
        mvc.perform(post("/v1/workspaces/ws").header("Authorization", bearer(token))).andExpect(status().isOk());
        mvc.perform(post("/v1/workspaces/ws/sessions/s1").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    /**
     * An empty batch is refused, which is what {@code minItems: 1} now says.
     *
     * <p>Nothing about this changed with the schema — {@code @NotEmpty} has answered 400 since the
     * record was written. The document said {@code minItems: 0} because springdoc let {@code @Size}
     * overwrite what {@code @NotEmpty} had set, so a client generated from it believed this body was
     * valid, sent it, and got a 400 with nothing in the description to explain why.
     */
    @Test
    void anEmptyMessageBatchIsRefusedAsTheDocumentSays() throws Exception {
        assertThat(publishedBound("CreateMessages", "messages", "minItems")).isEqualTo(1);

        mvc.perform(post("/v1/workspaces/ws/sessions/s1/messages").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"messages\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("bad_request"));
    }

    /** The published ceiling is the constant the record declares, not a number that drifted from it. */
    @Test
    void theConclusionCeilingIsPublished() {
        assertThat(publishedBound("CreateConclusion", "content", "maxLength")).isEqualTo(Requests.MAX_CONTENT_CHARS);
        assertThat(publishedBound("CreateConclusion", "content", "minLength")).isEqualTo(1);
    }

    /** A conclusion at the published ceiling is stored, so the cap is not quietly below what it says. */
    @Test
    void aConclusionAtTheCeilingIsAccepted() throws Exception {
        int max = publishedBound("CreateConclusion", "content", "maxLength");

        mvc.perform(inject(hangul(max))).andExpect(status().isOk()).andExpect(jsonPath("$.content").isNotEmpty());
    }

    /** One character past the ceiling is a 400 rather than anything the database has to refuse. */
    @Test
    void aConclusionPastTheCeilingIsRefusedRatherThanFailingInTheDatabase() throws Exception {
        int max = publishedBound("CreateConclusion", "content", "maxLength");

        mvc.perform(inject(hangul(max + 1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
    }

    /**
     * The length that used to reach the database and fail there is now stored.
     *
     * <p>1500 Hangul characters answered 500 {@code internal_error}: {@code ix_concl_norm} was a btree
     * over {@code (workspace_name, observer, observed, content_norm)}, PostgreSQL refuses an index
     * entry above 2704 bytes with SQLSTATE 54000, and that is not a
     * {@code DataIntegrityViolationException} — so it reached {@code ApiExceptionHandler}'s catch-all
     * and a caller's over-long field was logged at ERROR as a server fault. Then it answered 400,
     * because {@code @Size(max = 800)} took the field out of reach of the failure without closing the
     * route. {@code V13__conclusion_norm_hash_index.sql} indexes {@code md5(content_norm)}, so the
     * entry no longer carries the content and this is a 200 with the row stored.
     *
     * <p>The whole text is compared rather than only the status, and that is a read of the stored row:
     * {@code ConclusionController.create} answers from {@code conclusions.find} rather than from the
     * request, so the body is what came back out of the database.
     */
    @Test
    void aConclusionLongerThanABtreeEntryIsStoredRatherThanFailingInTheDatabase() throws Exception {
        String content = hangul(1_500);

        mvc.perform(inject(content)).andExpect(status().isOk()).andExpect(jsonPath("$.content").value(content));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder inject(String content) {
        return post("/v1/workspaces/ws/conclusions").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"observer\":\"alice\",\"observed\":\"alice\",\"session\":\"s1\",\"content\":\"" + content
                        + "\"}");
    }

    /**
     * {@code n} Hangul syllables: three UTF-8 bytes each, against one for the Latin the same character
     * count would cost. The byte width is the point — every boundary this class exercises is a byte
     * boundary underneath, and Hangul is what this system's corpus is written in.
     */
    private static String hangul(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) (0xAC00 + (i * 37 % 11172)));
        }
        return sb.toString();
    }

    private static int publishedBound(String schema, String property, String keyword) {
        JsonNode node = document().path("components").path("schemas").path(schema).path("properties").path(property)
                .path(keyword);
        assertThat(node.isInt()).as("%s.%s has no %s in docs/openapi.json", schema, property, keyword).isTrue();
        return node.asInt();
    }

    private static JsonNode document() {
        String configured = System.getProperty("aimon.memory.docs.dir");
        Path docs = configured == null || configured.isBlank() ? Paths.get("docs") : Paths.get(configured);
        Path file = docs.resolve("openapi.json");
        try {
            return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }
}
