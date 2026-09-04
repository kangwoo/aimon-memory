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

    /**
     * Delegation, which is what the nested scopes are for: a service holding a workspace token hands
     * a client a token for one session, without an admin key anywhere near it.
     */
    @Test
    void aWorkspaceTokenCanMintANarrowerTokenForItsOwnWorkspace() throws Exception {
        String body = "{\"scope\":\"session\",\"workspace\":\"ws\",\"session\":\"s1\"}";

        mvc.perform(post("/v1/tokens").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", bearer(workspaceToken("ws"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("session"));

        mvc.perform(post("/v1/tokens").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("session"));
    }

    /** Delegation only ever narrows. Every widening is a route back to a token the caller never had. */
    @Test
    void mintingCannotWidenTheTokenThatAsked() throws Exception {
        // A broader scope than the caller holds.
        mvc.perform(post("/v1/tokens").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"admin\"}")
                        .header("Authorization", bearer(workspaceToken("ws"))))
                .andExpect(status().isForbidden());

        // Another workspace.
        mvc.perform(post("/v1/tokens").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"peer\",\"workspace\":\"other\",\"peer\":\"alice\"}")
                        .header("Authorization", bearer(workspaceToken("ws"))))
                .andExpect(status().isForbidden());
    }

    /**
     * A peer token cannot mint at all — the route requires a workspace scope, which no peer or
     * session token satisfies. It matters more than it looks: a session token carries no peer, and a
     * token with no peer may post as any participant, so minting one from a peer token would launder
     * the speaker check on every message.
     */
    @Test
    void aPeerOrSessionTokenCannotMintAnything() throws Exception {
        String body = "{\"scope\":\"session\",\"workspace\":\"ws\",\"session\":\"s1\"}";

        mvc.perform(post("/v1/tokens").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", bearer(peerToken("ws", "alice"))))
                .andExpect(status().isForbidden());

        mvc.perform(post("/v1/tokens").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", bearer(sessionToken("ws", "s1", true))))
                .andExpect(status().isForbidden());
    }

    /** Expiry is the only thing that ends a leaked token, so a request cannot ask for an endless one. */
    @Test
    void aTokenLifetimeIsCapped() throws Exception {
        mvc.perform(post("/v1/tokens").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"workspace\",\"workspace\":\"ws\",\"lifetimeSeconds\":315360000}")
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_lifetime"));
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

    /**
     * The speaker is body data, not a path variable, so nothing in the route table was checking it. A
     * peer token could post a message signed with another peer's name — stored as theirs, fanned out
     * into every observer's memory, and derived into conclusions about them.
     */
    @Test
    void aPeerTokenCannotPostMessagesAsAnotherPeer() throws Exception {
        mvc.perform(post("/v1/workspaces/ws/sessions/s1").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk());

        mvc.perform(post("/v1/workspaces/ws/sessions/s1/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"peer\":\"alice\",\"content\":\"I agreed to pay 5000\"}]}")
                        .header("Authorization", bearer(peerToken("ws", "bob"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));

        mvc.perform(post("/v1/workspaces/ws/sessions/s1/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"peer\":\"bob\",\"content\":\"I agreed to pay 5000\"}]}")
                        .header("Authorization", bearer(peerToken("ws", "bob"))))
                .andExpect(status().isOk());
    }

    /**
     * A session token names a conversation rather than a participant, and a conversation has several.
     * Transcribing all of them is what that token is for, so it is deliberately not narrowed here —
     * the messages still land in the one session the token is scoped to.
     */
    @Test
    void aSessionTokenStillSpeaksForEveryoneInItsOwnSession() throws Exception {
        mvc.perform(post("/v1/workspaces/ws/sessions/s1").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk());

        mvc.perform(post("/v1/workspaces/ws/sessions/s1/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"peer\":\"alice\",\"content\":\"hello\"},"
                                + "{\"peer\":\"assistant\",\"content\":\"hi\"}]}")
                        .header("Authorization", bearer(sessionToken("ws", "s1", false))))
                .andExpect(status().isOk());

        mvc.perform(post("/v1/workspaces/ws/sessions/other/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"peer\":\"alice\",\"content\":\"hello\"}]}")
                        .header("Authorization", bearer(sessionToken("ws", "s1", false))))
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
