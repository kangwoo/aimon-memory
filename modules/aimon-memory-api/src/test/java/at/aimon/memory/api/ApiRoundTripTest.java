package at.aimon.memory.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The HTTP surface end to end: hierarchy, ingestion, direct injection, recall, audit. */
class ApiRoundTripTest extends ApiTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private String token;

    @BeforeEach
    void seed() throws Exception {
        token = adminToken();
        mvc.perform(post("/v1/workspaces/ws").header("Authorization", bearer(token))).andExpect(status().isOk());
        mvc.perform(post("/v1/workspaces/ws/peers/alice").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/workspaces/ws/sessions/s1").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    private String postJson(String path, String body) throws Exception {
        return mvc.perform(
                post(path).contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void messagesAreStoredAndReturnedInSequence() throws Exception {
        String response = postJson("/v1/workspaces/ws/sessions/s1/messages", """
                {"messages":[
                  {"peer":"alice","content":"I work at a bank in Seoul."},
                  {"peer":"alice","content":"I commute by subway."}]}
                """);

        JsonNode saved = MAPPER.readTree(response);
        org.assertj.core.api.Assertions.assertThat(saved).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(saved.get(0).path("seq").asLong()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(saved.get(1).path("seq").asLong()).isEqualTo(2);
        // Counted at write time so the batch gate never has to re-tokenise.
        org.assertj.core.api.Assertions.assertThat(saved.get(0).path("tokenCount").asInt()).isGreaterThan(0);

        mvc.perform(get("/v1/workspaces/ws/sessions/s1/messages").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
    }

    /**
     * A directly injected fact goes through the same dedup and the same audit log as anything the
     * deriver produces, and recall returns it with a full explain breakdown.
     */
    @Test
    void injectedConclusionsAreRecallableWithExplain() throws Exception {
        String created = postJson("/v1/workspaces/ws/conclusions", """
                {"observer":"alice","observed":"alice","session":"s1",
                 "content":"alice works at a bank in seoul","entities":["seoul"]}
                """);
        String id = MAPPER.readTree(created).path("id").asText();

        String recalled = postJson("/v1/workspaces/ws/recall",
                "{\"query\":\"bank seoul\",\"observer\":\"alice\",\"observed\":\"alice\"}");

        JsonNode hits = MAPPER.readTree(recalled).path("hits");
        org.assertj.core.api.Assertions.assertThat(hits).hasSize(1);
        JsonNode explain = hits.get(0).path("explain");
        org.assertj.core.api.Assertions.assertThat(explain.path("weights")).hasSize(6);
        for (String signal : java.util.List.of("sem", "kw", "ent", "reinf", "rec", "lvl")) {
            org.assertj.core.api.Assertions.assertThat(explain.has(signal)).as(signal).isTrue();
        }

        // The wire type must not leak the internal columns.
        org.assertj.core.api.Assertions.assertThat(hits.get(0).path("conclusion").has("contentNorm")).isFalse();
        org.assertj.core.api.Assertions.assertThat(hits.get(0).path("conclusion").has("embedding")).isFalse();

        mvc.perform(get("/v1/workspaces/ws/conclusions/" + id + "/events").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].event").value("add"))
                .andExpect(jsonPath("$[0].actor").value("api"));
    }

    @Test
    void entityProvenanceReachesTheOriginalMessages() throws Exception {
        postJson("/v1/workspaces/ws/sessions/s1/messages",
                "{\"messages\":[{\"peer\":\"alice\",\"content\":\"I work at a bank in Seoul.\"}]}");
        postJson("/v1/workspaces/ws/conclusions", """
                {"observer":"alice","observed":"alice","session":"s1",
                 "content":"alice works at a bank in seoul","entities":["seoul"]}
                """);

        mvc.perform(get("/v1/workspaces/ws/recall/provenance").param("entity", "seoul").param("observer", "alice")
                .param("observed", "alice").header("Authorization", bearer(token))).andExpect(status().isOk())
                .andExpect(jsonPath("$.entity").value("seoul"))
                .andExpect(jsonPath("$.conclusions[0].conclusion.content").value("alice works at a bank in seoul"));

        mvc.perform(get("/v1/workspaces/ws/recall/provenance").param("entity", "nowhere").param("observer", "alice")
                .param("observed", "alice").header("Authorization", bearer(token))).andExpect(status().isNotFound());
    }

    @Test
    void deletingAConclusionIsRecordedAndHidesItFromRecall() throws Exception {
        String created = postJson("/v1/workspaces/ws/conclusions", """
                {"observer":"alice","observed":"alice","session":"s1",
                 "content":"alice works at a bank","entities":[]}
                """);
        String id = MAPPER.readTree(created).path("id").asText();

        mvc.perform(delete("/v1/workspaces/ws/conclusions/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        String recalled = postJson("/v1/workspaces/ws/recall",
                "{\"query\":\"bank\",\"observer\":\"alice\",\"observed\":\"alice\"}");
        org.assertj.core.api.Assertions.assertThat(MAPPER.readTree(recalled).path("hits")).isEmpty();

        mvc.perform(get("/v1/workspaces/ws/conclusions/" + id + "/events").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$[0].event").value("delete"));
    }

    /**
     * A rejected filter is 422 with a reason, not 200 with no rows. Returning an empty page would tell
     * the caller there is no data when in fact their predicate was thrown away.
     */
    @Test
    void aRejectedFilterIsUnprocessableRatherThanEmpty() throws Exception {
        mvc.perform(post("/v1/workspaces/ws/recall").contentType(MediaType.APPLICATION_JSON).content("""
                {"query":"x","observer":"alice","observed":"alice",
                 "filter":{"observer":"someone-else"}}
                """).header("Authorization", bearer(token))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("bad_filter"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("unknown filter field")));

        mvc.perform(post("/v1/workspaces/ws/recall").contentType(MediaType.APPLICATION_JSON).content("""
                {"query":"x","observer":"alice","observed":"alice",
                 "filter":{"times_derived":{"gte":"lots"}}}
                """).header("Authorization", bearer(token))).andExpect(status().isUnprocessableEntity());
    }

    /**
     * Injecting a fact about someone new must work, the way posting a message about them does.
     * It used to fail with a foreign-key violation reported as a 500.
     */
    @Test
    void injectingAConclusionCreatesThePeersItNames() throws Exception {
        String created = postJson("/v1/workspaces/ws/conclusions", """
                {"observer":"newcomer","observed":"stranger","session":"brand-new",
                 "content":"stranger prefers email","entities":[]}
                """);
        org.assertj.core.api.Assertions.assertThat(MAPPER.readTree(created).path("id").asText()).isNotBlank();

        mvc.perform(get("/v1/workspaces/ws/peers/newcomer").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/workspaces/ws/sessions/brand-new").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    /** A constraint the database refuses is the caller's problem, not a server fault. */
    @Test
    void aConstraintViolationIsAConflictNotAServerError() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/v1/workspaces/ws/sessions/s1/peers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"peers\":[{\"peer\":\"ghost\"}]}").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        // session_peers requires the session to exist; naming one that does not is a 409, not a 500.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/v1/workspaces/ws/sessions/never-created/peers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"peers\":[{\"peer\":\"ghost\"}]}").header("Authorization", bearer(token)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("constraint_violation"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("session_peers"))));
    }

    @Test
    void aMalformedBodyIsABadRequest() throws Exception {
        mvc.perform(post("/v1/workspaces/ws/recall").contentType(MediaType.APPLICATION_JSON)
                .content("{\"observer\":\"alice\",\"observed\":\"alice\"}").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("bad_request"));
    }

    /** With no provider configured the chat route must say so, not fail as an internal error. */
    @Test
    void chatWithoutAConfiguredProviderIsUnavailable() throws Exception {
        mvc.perform(post("/v1/workspaces/ws/chat").contentType(MediaType.APPLICATION_JSON).content("""
                {"question":"where does alice work?","observer":"alice",
                 "observed":"alice","session":"s1","reasoningLevel":"minimal"}
                """).header("Authorization", bearer(token))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("llm_not_configured"));
    }

    @Test
    void workspaceConfigurationRoundTripsAndTakesEffect() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/v1/workspaces/ws/configuration").contentType(MediaType.APPLICATION_JSON)
                .content("{\"configuration\":{\"language\":\"ko\",\"recall.half_life_days\":30}}")
                .header("Authorization", bearer(token))).andExpect(status().isOk())
                .andExpect(jsonPath("$.configuration.language").value("ko"));
    }
}
