package at.aimon.memory.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * An injected conclusion's entity names have to be able to name something.
 *
 * <p>Distinct from {@code NestedValidationTest}, and the difference is the point. Those constraints
 * were declared, published in {@code docs/openapi.json} as {@code minLength: 1}, and simply never run
 * — restoring them changed the implementation to match a contract already in print. This one is new:
 * the published schema for {@code entities} said {@code {"items":{"type":"string"}}} and promised
 * nothing about the elements, so a caller sending a blank one was not breaking any stated rule.
 *
 * <p>What justifies making the rule is measured harm rather than tidiness. A blank name became an
 * entity node with an empty {@code name_norm} and {@code name_display} — one node, since every blank
 * normalises to the same key — which held an edge from every unrelated conclusion that carried one,
 * survived the orphan sweep because it had edges, and sat in the vector index. A query landing near
 * its vector handed the {@code ent} signal to all of those conclusions at once, with the explain
 * payload reporting {@code matchedEntities: [""]}.
 *
 * <p>A container-element constraint, and worth noting after the lesson one PR earlier: unlike
 * {@code @Valid} cascade into a nested type, {@code List<@NotBlank String>} is evaluated without any
 * further annotation on the field.
 */
class EntityNameValidationTest extends ApiTestBase {

    private String token;

    @BeforeEach
    void seed() throws Exception {
        token = adminToken();
        mvc.perform(post("/v1/workspaces/ws").header("Authorization", bearer(token))).andExpect(status().isOk());
        mvc.perform(post("/v1/workspaces/ws/sessions/s1").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    private String body(String entitiesJson) {
        return "{\"observer\":\"a\",\"observed\":\"b\",\"session\":\"s1\",\"content\":\"alice was in 서울\","
                + "\"entities\":" + entitiesJson + "}";
    }

    private void expectBadRequest(String entitiesJson) throws Exception {
        mvc.perform(post("/v1/workspaces/ws/conclusions").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body(entitiesJson))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
    }

    @Test
    void aBlankEntityNameIsRefused() throws Exception {
        expectBadRequest("[\"   \"]");
        expectBadRequest("[\"\"]");
        expectBadRequest("[\"\\t\"]");
    }

    /** One blank among real names is still refused; the array is wrong, not merely partly usable. */
    @Test
    void aBlankAlongsideRealNamesIsRefused() throws Exception {
        expectBadRequest("[\"서울\",\"\"]");
    }

    /**
     * A null element is a 400 rather than a 500.
     *
     * <p>It was already refused, but by {@code List.copyOf} inside {@code ConclusionDraft}, which
     * rejects null elements with a bare NullPointerException — so the catch-all reported a client's
     * malformed array as {@code internal_error} and logged a stack trace at ERROR. That is the failure
     * {@code ClientErrorStatusTest} exists to keep out of the error-rate metric.
     */
    @Test
    void aNullEntityNameIsABadRequestRatherThanAServerError() throws Exception {
        expectBadRequest("[null]");
    }

    @Test
    void realEntityNamesAreStillAccepted() throws Exception {
        mvc.perform(post("/v1/workspaces/ws/conclusions").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body("[\"서울\",\"강남\"]"))).andExpect(status().isOk());
    }

    /** An absent or empty list is not a violation: entities are optional on an injected fact. */
    @Test
    void anOmittedOrEmptyEntityListIsStillAllowed() throws Exception {
        mvc.perform(post("/v1/workspaces/ws/conclusions").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body("[]"))).andExpect(status().isOk());
        mvc.perform(post("/v1/workspaces/ws/conclusions").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"observer\":\"a\",\"observed\":\"b\",\"session\":\"s1\",\"content\":\"no entities\"}"))
                .andExpect(status().isOk());
    }

    /**
     * Whitespace this endpoint accepts and the pipeline drops is refused here too.
     *
     * <p>{@code @NotBlank} is specified in terms of {@code String.trim()}, which strips only characters
     * at or below {@code U+0020}; {@code EntityPipeline.isUsable} is {@code String.isBlank()}, which is
     * {@code Character.isWhitespace}. EN QUAD is whitespace to the second and not to the first, so
     * before {@code @UsableName} this request was answered 200 and the name was then dropped without
     * trace — the silent disappearance the 400 exists to prevent, arriving through the constraint
     * itself. Measured at the time: {@code POST} 200, and provenance for the same string 404.
     *
     * <p>The escape is written out rather than pasted so the character stays visible in the source.
     */
    @Test
    void whitespaceAboveU0020IsRefusedRatherThanSilentlyDropped() throws Exception {
        expectBadRequest("[\"\\u2000\"]");
        expectBadRequest("[\"서울\",\"\\u2000\"]");
    }
}
