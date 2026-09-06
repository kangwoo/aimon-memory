package at.aimon.memory.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.memory.core.MemoryException;
import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.spi.LlmClient;
import at.aimon.memory.engine.dream.DreamerService;
import at.aimon.memory.store.repo.DreamRepository;
import at.aimon.memory.testkit.db.PostgresSupport;
import at.aimon.memory.testkit.stub.StubLlmClient;

/**
 * A failed dream's {@code error} column is a response body, and a driver wrote most of what went in it.
 *
 * <p>{@code Dtos.DreamResponse} returns {@code dreams.error} and {@code GET /v1/workspaces/{ws}/dreams}
 * is a <b>200</b>, so the column is the one place an exception message reaches a caller without
 * passing {@code ApiExceptionHandler}. The previous sweep closed that route for {@code
 * FixtureMissException} — see {@code FixtureMissBodyTest} — by moving the wire/log split onto {@code
 * MemoryException.publicMessage}. It left the larger half open: {@code DreamerService} catches {@code
 * RuntimeException}, and {@code publicMessageOf} handed anything that was not a {@code MemoryException}
 * its own message.
 *
 * <p><b>Measured before the fix.</b> The dream below wrote its derived conclusion into a {@code
 * conclusions} table carrying a not-null column, and the endpoint answered with a 1,462-byte 200 whose
 * {@code error} held the whole {@code INSERT} — every column name, and the dedup scope out of the
 * {@code ON CONFLICT} clause — followed by {@code Detail: Failing row contains (…)}: the conclusion id,
 * the pair, the derived text, its normalised and analysed forms, its content hash and the head of its
 * vector. The identical exception raised by an ordinary {@code POST /conclusions} was 124 bytes, because
 * {@code ApiExceptionHandler.constraint} refuses exactly this text. One door, not the other.
 *
 * <p><b>On the ALTER.</b> Nothing this build sends can make one of its own writes violate a constraint:
 * every insert is {@code ON CONFLICT DO UPDATE} or {@code DO NOTHING}, blank entity names are filtered
 * above the check that would reject them, and {@code EmbeddingDimensionCheck} refuses to start on the
 * width mismatch. What a dream does meet is the database being in a state this build did not put it in
 * — a migration half-applied under a rolling deploy, the shape below: a not-null column added with a
 * default, the default dropped in a later step, the previous version still writing. It is also the
 * ordinary shape of every infrastructure failure that reaches the same catch with a message of its own
 * (a connection that cannot be acquired names the host, a statement timeout names nothing, a deadlock
 * names process ids), which is why the fix is at the sink rather than at any throw site.
 */
class DreamErrorBodyLeakTest extends ApiTestBase {

    /** Distinctive enough that a substring assertion cannot pass by accident. */
    private static final String STORED = "12 Rosengasse";

    @MockitoBean
    private LlmClient llm;

    @Autowired
    private DreamerService dreamer;

    private String token;

    @BeforeEach
    void seed() throws Exception {
        token = adminToken();
        mvc.perform(post("/v1/workspaces/ws").header("Authorization", bearer(token))).andExpect(status().isOk());
        mvc.perform(post("/v1/workspaces/ws/sessions/s").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    /** The container is shared by every test in this JVM, so the drift has to be undone either way. */
    @AfterEach
    void undoTheHalfAppliedMigration() throws SQLException {
        execute("ALTER TABLE conclusions DROP COLUMN IF EXISTS tenant_id");
    }

    @Test
    void aDatabaseFailureInsideADreamIsNotQuotedInItsTwoHundred() throws Exception {
        String premise = storeAPremise();
        answerWith("""
                {"conclusions":[{"content":"bob's home is in the Rosengasse",
                  "sourceIds":["%s"],"entities":["Rosengasse"],"confidence":1.0}]}
                """.formatted(premise));
        breakTheNextWrite();

        runTheDream();

        mvc.perform(get("/v1/workspaces/ws/dreams").header("Authorization", bearer(token)).param("observer", "alice")
                .param("observed", "bob")).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("failed"))
                .andExpect(jsonPath("$[0].error").value("an internal failure; see the server log"))
                // The statement and the schema it names.
                .andExpect(content().string(Matchers.not(Matchers.containsString("INSERT INTO"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("content_hash"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("tenant_id"))))
                // The failing row: the derived conclusion, and the stored fact it was derived from.
                .andExpect(content().string(Matchers.not(Matchers.containsString("Failing row contains"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Rosengasse"))));
    }

    /**
     * The field is still worth returning, which is why it was not simply dropped from the response.
     *
     * <p>A dream fails in the worker, so there is no 5xx for the client to read and no {@code code}
     * field on {@code DreamResponse}: this string is the whole channel. Removing it would leave {@code
     * status: "failed"} and nothing to separate "this deployment has no model provider, go and
     * configure one" from "the server broke, go and ask the operator" — the first of which is the
     * caller's to fix. So a {@code MemoryException} still arrives whole, exactly as it does in a 5xx
     * body, and only the failures nobody here worded are summarised.
     */
    @Test
    void aFailureThisBuildWordedStillReachesTheCaller() throws Exception {
        storeAPremise();
        given(llm.structured(any(), any()))
                .willThrow(new MemoryException("llm_not_configured", "no model provider is configured"));

        runTheDream();

        mvc.perform(get("/v1/workspaces/ws/dreams").header("Authorization", bearer(token)).param("observer", "alice")
                .param("observed", "bob")).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("failed"))
                .andExpect(jsonPath("$[0].error").value("no model provider is configured"));
    }

    /**
     * The other door, on the same broken schema, for the comparison the fix is about.
     *
     * <p>{@code ApiExceptionHandler.constraint} has always refused the driver's message here. That is
     * the behaviour the dream path now matches rather than a new rule being invented for it.
     */
    @Test
    void theHttpDoorAnswersTheSameExceptionWithOneSentence() throws Exception {
        breakTheNextWrite();

        mvc.perform(post("/v1/workspaces/ws/conclusions").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"observer\":\"alice\",\"observed\":\"bob\",\"session\":\"s\","
                        + "\"content\":\"bob lives at " + STORED + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("constraint_violation"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Failing row contains"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString(STORED))));
    }

    /** A dream needs something to work from, or it completes with nothing produced and never writes. */
    private String storeAPremise() throws Exception {
        String created = mvc
                .perform(post("/v1/workspaces/ws/conclusions").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observer\":\"alice\",\"observed\":\"bob\",\"session\":\"s\","
                                + "\"content\":\"bob lives at " + STORED + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(created).get("id").asText();
    }

    private void answerWith(String json) {
        StubLlmClient stub = StubLlmClient.returning(json);
        given(llm.structured(any(), any()))
                .willAnswer(call -> stub.structured(call.getArgument(0), call.getArgument(1)));
    }

    /**
     * A migration that adds a not-null column with a default and drops the default in a later step,
     * with the previous version of the application still writing through the old statement.
     */
    private static void breakTheNextWrite() throws SQLException {
        execute("ALTER TABLE conclusions ADD COLUMN tenant_id TEXT NOT NULL DEFAULT 'legacy'");
        execute("ALTER TABLE conclusions ALTER COLUMN tenant_id DROP DEFAULT");
    }

    /** Exactly what the worker does with a dream, including rethrowing once the failure is recorded. */
    private void runTheDream() {
        PairKey pair = new PairKey("ws", "alice", "bob");
        DreamRepository.Dream dream = dreamer.scheduleNow(pair, DreamRepository.DreamType.CONSOLIDATE).orElseThrow();
        try {
            dreamer.run(dream);
        } catch (RuntimeException expected) {
            // Recording it is what this class is about; WorkerLoop is what catches it in production.
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = PostgresSupport.dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
