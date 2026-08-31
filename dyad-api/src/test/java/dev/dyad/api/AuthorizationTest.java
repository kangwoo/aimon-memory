package dev.dyad.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AuthorizationTest extends ApiTestBase {

    @BeforeEach
    void seedWorkspace() throws Exception {
        mvc.perform(post("/v1/workspaces/ws").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk());
    }

    @Test
    void anUnauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/v1/workspaces/ws"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));

        mvc.perform(get("/v1/workspaces/ws").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/workspaces/ws").header("Authorization", adminToken()))
                .andExpect(status().isUnauthorized());
    }

    /** Scopes nest: a broad token satisfies a narrow requirement, never the reverse. */
    @Test
    void aNarrowTokenCannotReachABroadRoute() throws Exception {
        mvc.perform(get("/v1/workspaces").header("Authorization", bearer(workspaceToken("ws"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));

        mvc.perform(get("/v1/workspaces").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk());
    }

    /** Minting tokens is the one thing a workspace token must never do. */
    @Test
    void onlyAnAdminTokenCanMintTokens() throws Exception {
        String body = "{\"scope\":\"session\",\"workspace\":\"ws\",\"session\":\"s1\"}";

        mvc.perform(post("/v1/tokens").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", bearer(workspaceToken("ws"))))
                .andExpect(status().isForbidden());

        mvc.perform(post("/v1/tokens").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("session"));
    }

    /** A token that names nothing is a workspace token wearing a session token's label. */
    @Test
    void aScopedTokenMustNameWhatItIsScopedTo() throws Exception {
        mvc.perform(post("/v1/tokens").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"session\",\"workspace\":\"ws\"}")
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_scope"));
    }

    /** The tenancy boundary. A workspace token for one tenant must not read another's. */
    @Test
    void aTokenCannotReachAnotherWorkspace() throws Exception {
        mvc.perform(post("/v1/workspaces/other").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/workspaces/other").header("Authorization", bearer(workspaceToken("ws"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSessionTokenCannotReachAnotherSession() throws Exception {
        mvc.perform(get("/v1/workspaces/ws/sessions/other")
                        .header("Authorization", bearer(sessionToken("ws", "s1", true))))
                .andExpect(status().isForbidden());
    }

    /**
     * Two conditions, not one: the route must be marked member-read and the token must carry the flag.
     * The session token is the one that ends up in a browser, so a default that granted reads would
     * turn every such token into a session-history export.
     */
    @Test
    void memberReadNeedsBothTheRouteAndTheTokenToAllowIt() throws Exception {
        mvc.perform(post("/v1/workspaces/ws/sessions/s1").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/workspaces/ws/sessions/s1")
                        .header("Authorization", bearer(sessionToken("ws", "s1", true))))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/workspaces/ws/sessions/s1")
                        .header("Authorization", bearer(sessionToken("ws", "s1", false))))
                .andExpect(status().isForbidden());

        // Recall is not a member-read route at all: it reaches a whole pair's memory, not one session.
        mvc.perform(post("/v1/workspaces/ws/recall").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"x\",\"observer\":\"alice\",\"observed\":\"alice\"}")
                        .header("Authorization", bearer(sessionToken("ws", "s1", true))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPeerTokenCannotActAsAnotherPeer() throws Exception {
        mvc.perform(post("/v1/workspaces/ws/peers/alice").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/workspaces/ws/peers/alice")
                        .header("Authorization", bearer(peerToken("ws", "bob"))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/v1/workspaces/ws/peers/alice")
                        .header("Authorization", bearer(peerToken("ws", "alice"))))
                .andExpect(status().isOk());
    }
}
