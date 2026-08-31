package dev.dyad.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Regression: Spring's own request-handling failures fell through to the catch-all handler, so a
 * wrong verb, a truncated body and a non-numeric page number all came back as 500 with a stack trace
 * logged at ERROR. Client mistakes have to be distinguishable from server faults — otherwise the
 * error-rate metric that is supposed to reveal an outage is dominated by malformed requests.
 */
class ClientErrorStatusTest extends ApiTestBase {

    private String token;

    @BeforeEach
    void seed() throws Exception {
        token = adminToken();
        mvc.perform(post("/v1/workspaces/ws").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    @Test
    void anUnsupportedMethodIsMethodNotAllowed() throws Exception {
        mvc.perform(delete("/v1/workspaces").header("Authorization", bearer(token)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("method_not_allowed"));
    }

    @Test
    void aTruncatedBodyIsABadRequest() throws Exception {
        mvc.perform(post("/v1/tokens").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"))
                // The parser echoes the offending bytes; the response must not.
                .andExpect(jsonPath("$.message").value("request body is not valid JSON"));
    }

    @Test
    void aNonNumericPageIsABadRequest() throws Exception {
        mvc.perform(get("/v1/workspaces").param("page", "abc").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
    }

    @Test
    void aMissingRequiredParameterIsABadRequest() throws Exception {
        mvc.perform(get("/v1/workspaces/ws/conclusions").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
    }

    @Test
    void anUnsupportedContentTypeIsABadRequest() throws Exception {
        mvc.perform(post("/v1/tokens").header("Authorization", bearer(token))
                        .contentType(MediaType.TEXT_PLAIN).content("scope=admin"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Regression: an unknown session reached a bare {@code orElseThrow}, whose NoSuchElementException
     * the catch-all reported as a 500 — a caller's typo logged at ERROR and counted as a server fault.
     */
    @Test
    void contextForAnUnknownSessionIsNotFound() throws Exception {
        mvc.perform(get("/v1/workspaces/ws/sessions/does-not-exist/context")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    /** An authenticated caller asking for a path that does not exist gets told so. */
    @Test
    void anUnknownVersionedPathIsNotFound() throws Exception {
        mvc.perform(get("/v1/nope").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    /**
     * Authentication comes first, so an unauthenticated caller cannot map the surface: a real route,
     * a missing route and a route they lack the scope for are all 401 until they present a token.
     */
    @Test
    void anUnauthenticatedCallerCannotTellRoutesApart() throws Exception {
        for (String path : java.util.List.of("/v1/workspaces", "/v1/nope", "/v1/workspaces/ws")) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("unauthorized"));
        }
    }
}
