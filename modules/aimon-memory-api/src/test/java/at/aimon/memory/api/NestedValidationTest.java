package at.aimon.memory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Constraints on the elements of a request's lists are actually evaluated.
 *
 * <p>They were not, anywhere. Bean Validation does not descend into a collection unless the field
 * holding it is marked {@code @Valid}, so every {@code @NotBlank} inside {@link
 * at.aimon.memory.api.dto.Requests.NewMessage}, {@link
 * at.aimon.memory.api.dto.Requests.SessionPeerSpec} and {@link
 * at.aimon.memory.api.dto.Requests.ChatTurn} was decoration — declared, published in
 * {@code docs/openapi.json} as {@code minLength: 1}, and never run. The published description had
 * been advertising a contract the implementation did not keep.
 *
 * <p>What made it hard to notice is that none of the three failed loudly. A blank peer became a peer
 * row named with spaces; a blank chat turn became an extra message in a provider prompt; a blank
 * message became a stored row with no content. Nothing threw, so nothing said so.
 */
class NestedValidationTest extends ApiTestBase {

    private String token;

    @BeforeEach
    void seed() throws Exception {
        token = adminToken();
        mvc.perform(post("/v1/workspaces/ws").header("Authorization", bearer(token))).andExpect(status().isOk());
        mvc.perform(post("/v1/workspaces/ws/sessions/s1").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    private void expectBadRequest(MockHttpServletRequestBuilder request, String body) throws Exception {
        mvc.perform(
                request.header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("bad_request"));
    }

    private int statusOf(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(
                request.header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    // ── session rosters ─────────────────────────────────────────────────────

    /**
     * A blank peer name is refused rather than stored.
     *
     * <p>This is a change in what the endpoint accepts, not in how it labels a refusal it was already
     * making. {@code PeerRepository.getOrCreate} builds no key, so {@code Segments.required} never saw
     * these names: {@code {"peers":[{"peer":"   "}]}} answered 200 and left a peer row called three
     * spaces, joined to the session and observing every message in it.
     */
    @Test
    void aBlankPeerNameIsRefusedWhenAddingToARoster() throws Exception {
        expectBadRequest(post("/v1/workspaces/ws/sessions/s1/peers"), "{\"peers\":[{\"peer\":\"   \"}]}");
        expectBadRequest(post("/v1/workspaces/ws/sessions/s1/peers"), "{\"peers\":[{\"peer\":\"\"}]}");
    }

    /** The same body on the replace route, which is a second call site of the same record. */
    @Test
    void aBlankPeerNameIsRefusedWhenReplacingARoster() throws Exception {
        expectBadRequest(put("/v1/workspaces/ws/sessions/s1/peers"), "{\"peers\":[{\"peer\":\"   \"}]}");
    }

    /**
     * A missing peer name is a 400 rather than a 409.
     *
     * <p>It was already refused, but by the NOT NULL constraint underneath, which
     * {@code ApiExceptionHandler} reports as "the request conflicts with existing data" — an answer
     * that describes neither the problem nor the field.
     */
    @Test
    void aMissingPeerNameIsABadRequestRatherThanAConstraintViolation() throws Exception {
        expectBadRequest(post("/v1/workspaces/ws/sessions/s1/peers"), "{\"peers\":[{}]}");
    }

    /** A well-formed roster is untouched by any of this. */
    @Test
    void aValidRosterIsStillAccepted() throws Exception {
        mvc.perform(post("/v1/workspaces/ws/sessions/s1/peers").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"peers\":[{\"peer\":\"alice\"},{\"peer\":\"bob\",\"observeOthers\":false}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.peer=='alice')]").exists());
    }

    // ── chat history ────────────────────────────────────────────────────────

    /**
     * A blank turn in the history is refused rather than forwarded to the provider.
     *
     * <p>The consequential half of this fix. These turns used to reach {@code LlmMessage} and go out in
     * the prompt — the 503 below in the pre-fix run was the LLM layer being reached, which is how far a
     * malformed body travelled. Anthropic rejects an empty text block, so the eventual answer to a
     * client's typo was a 500.
     */
    @Test
    void aBlankChatHistoryTurnIsRefused() throws Exception {
        expectBadRequest(post("/v1/workspaces/ws/chat"), "{\"question\":\"q\",\"observer\":\"a\",\"observed\":\"b\","
                + "\"history\":[{\"role\":\"user\",\"content\":\"   \"}]}");
        expectBadRequest(post("/v1/workspaces/ws/chat"), "{\"question\":\"q\",\"observer\":\"a\",\"observed\":\"b\","
                + "\"history\":[{\"role\":\"\",\"content\":\"hello\"}]}");
        expectBadRequest(post("/v1/workspaces/ws/chat"),
                "{\"question\":\"q\",\"observer\":\"a\",\"observed\":\"b\",\"history\":[{}]}");
    }

    /** The streaming route shares the record, so it shares the constraint. */
    @Test
    void aBlankChatHistoryTurnIsRefusedOnTheStreamingRouteToo() throws Exception {
        expectBadRequest(post("/v1/workspaces/ws/chat/stream"),
                "{\"question\":\"q\",\"observer\":\"a\",\"observed\":\"b\","
                        + "\"history\":[{\"role\":\"user\",\"content\":\"\"}]}");
    }

    /**
     * A well-formed history is not rejected.
     *
     * <p>Asserted as "not a 400" rather than as a success: no model is configured in this tier, so a
     * request that clears validation goes on to fail at the provider with a 503. That is the point —
     * it got past validation, which is the only thing this test is about.
     */
    @Test
    void aValidChatHistoryIsNotRejected() throws Exception {
        int status = statusOf(post("/v1/workspaces/ws/chat"),
                "{\"question\":\"q\",\"observer\":\"a\",\"observed\":\"b\","
                        + "\"history\":[{\"role\":\"user\",\"content\":\"hello\"},"
                        + "{\"role\":\"assistant\",\"content\":\"hi\"}]}");

        assertThat(status).isNotEqualTo(400);
    }

    /** An absent history is still absent, not an empty-list violation. */
    @Test
    void anOmittedHistoryIsStillAllowed() throws Exception {
        int status = statusOf(post("/v1/workspaces/ws/chat"),
                "{\"question\":\"q\",\"observer\":\"a\",\"observed\":\"b\"}");

        assertThat(status).isNotEqualTo(400);
    }

    // ── messages, which the same omission covered ───────────────────────────

    /** The third member of the family, kept here so all three are asserted in one place. */
    @Test
    void aBlankMessageContentIsRefused() throws Exception {
        expectBadRequest(post("/v1/workspaces/ws/sessions/s1/messages"),
                "{\"messages\":[{\"peer\":\"alice\",\"content\":\"   \"}]}");
    }
}
