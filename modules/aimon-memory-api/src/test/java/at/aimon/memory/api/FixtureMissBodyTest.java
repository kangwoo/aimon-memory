package at.aimon.memory.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.engine.dream.DreamerService;
import at.aimon.memory.store.repo.DreamRepository;

/**
 * A {@code fixture_miss} 503 does not hand back the prompt or the server's paths.
 *
 * <p>{@code FixtureMissException}'s message is a test-harness diagnostic — the fixtures directory as
 * an absolute path, and the entire canonical request: the system prompt, the tool schemas, every
 * turn. {@code ApiExceptionHandler} used to copy it into the body like any other
 * {@code MemoryException}.
 *
 * <p><b>Why this is a production path and not a test-only one.</b> Replay is not opt-in.
 * {@code LlmMode.fromEnvironment} returns {@code REPLAY} when neither {@code AIMON_MEMORY_LLM_MODE}
 * nor {@code aimon.memory.llm.mode} is set — `CONTRIBUTING` says so in as many words — and
 * {@code MemoryConfiguration.llmClient} wraps every configured provider in
 * {@code RecordingChatBackend} unconditionally. So a deployment that sets a provider and an API key
 * and leaves the mode alone runs on fixtures it has never recorded, and answers every model call with
 * this 503. That is the only configuration this class adds: a provider, and no mode.
 */
@TestPropertySource(properties = {"aimon.memory.llm.provider=openai", "aimon.memory.llm.open-ai-api-key=sk-test-key"})
class FixtureMissBodyTest extends ApiTestBase {

    @Autowired
    private DreamerService dreamer;

    private String token;

    @BeforeEach
    void seed() throws Exception {
        token = adminToken();
        mvc.perform(post("/v1/workspaces/ws").header("Authorization", bearer(token))).andExpect(status().isOk());
    }

    @Test
    void theFixtureMissBodyCarriesNeitherThePromptNorTheServersPaths() throws Exception {
        mvc.perform(post("/v1/workspaces/ws/chat").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"what is alice's address\",\"observer\":\"alice\",\"observed\":\"bob\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("fixture_miss"))
                // The prompt: a phrase from the system prompt, and the tool schema it carries.
                .andExpect(content().string(Matchers.not(Matchers.containsString("You answer questions about"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("search_messages"))))
                // The absolute path of the fixtures directory, and the re-record recipe beside it.
                .andExpect(content().string(Matchers.not(Matchers.containsString("test-fixtures"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("AIMON_MEMORY_LLM_MODE"))));
    }

    /**
     * The code still separates this from every other 503, so an operator who sees it knows the
     * deployment is on fixtures rather than that a provider is down. Losing that would make the
     * misconfiguration above harder to spot, not easier.
     */
    @Test
    void theCallerStillLearnsWhichFailureThisIs() throws Exception {
        mvc.perform(post("/v1/workspaces/ws/chat").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"anything\",\"observer\":\"alice\",\"observed\":\"bob\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("fixture_miss"))
                .andExpect(jsonPath("$.message")
                        .value("no recorded LLM fixture matches this request; see the server log"));
    }

    /**
     * The same message, arriving by the route that does not pass through {@code ApiExceptionHandler}.
     *
     * <p>A dream is run by the worker, not by a request, and {@code DreamerService} records the
     * failure by storing the exception's message in {@code dreams.error}. {@code Dtos.DreamResponse}
     * returns that column, so {@code GET /dreams} is a <b>200</b> that carries an exception message —
     * a second way out for the very value the 503 above was closed for, and the reason the wire/log
     * split lives on {@code MemoryException} rather than in the handler.
     *
     * <p>The dream needs a conclusion to work from, or it completes with nothing produced and never
     * reaches the model.
     */
    @Test
    void aFailedDreamDoesNotReturnThePromptInItsTwoHundred() throws Exception {
        mvc.perform(post("/v1/workspaces/ws/sessions/s").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/workspaces/ws/conclusions").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"observer\":\"alice\",\"observed\":\"bob\",\"session\":\"s\","
                        + "\"content\":\"bob lives at 12 Rosengasse\"}"))
                .andExpect(status().isOk());

        PairKey pair = new PairKey("ws", "alice", "bob");
        DreamRepository.Dream dream = dreamer.scheduleNow(pair, DreamRepository.DreamType.CONSOLIDATE).orElseThrow();
        // Exactly what the worker does with it, including rethrowing after the failure is recorded.
        try {
            dreamer.run(dream);
        } catch (RuntimeException expected) {
            // The fixture miss. Recording it is what this test is about.
        }

        mvc.perform(get("/v1/workspaces/ws/dreams")
                .header("Authorization", bearer(token)).param("observer", "alice").param("observed", "bob"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("failed"))
                .andExpect(jsonPath("$[0].error")
                        .value("no recorded LLM fixture matches this request; see the server log"))
                // The dream prompt, the stored conclusion it was built from, the fixtures path, and
                // the recipe naming the variable that turns replay off.
                .andExpect(content().string(Matchers.not(Matchers.containsString("Derive only conclusions"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Rosengasse"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("test-fixtures"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("AIMON_MEMORY_LLM_MODE"))));
    }
}
