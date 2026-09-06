package at.aimon.memory.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.memory.testkit.db.PostgresSupport;

/**
 * A 500 must not carry stored data back to the caller.
 *
 * <p>{@code ApiExceptionHandler.memory} copies a {@code MemoryException}'s message into the response
 * body, which is the design and stays the design: {@code store_failed} naming a workspace, an entity
 * or a session repeats values the caller just sent, so nothing new escapes. {@code Jsonb} was the
 * exception. It threw {@code "cannot read jsonb object: " + json} where {@code json} is a column read
 * back out of the database — never something the caller sent — and the API answered with it.
 *
 * <p>{@code internal_metadata} is the sharpest case and the second test below. {@code
 * Dtos.SessionResponse} deliberately omits it, so no successful response can ever contain it; the
 * error path was the one route by which it reached a client.
 *
 * <p><b>On the UPDATE.</b> These tests write the malformed value with SQL rather than through the
 * API because the API cannot produce it: every writer into these columns is typed {@code Map<String,
 * Object>} or {@code List<String>}, and Postgres validates {@code jsonb} on the way in. That is also
 * the honest statement of reach — the failure belongs to bytes this build did not write (an
 * operator's UPDATE, a restore, a hand-written migration), which is exactly when a 500 is most likely
 * to be read by someone who should not see the row.
 */
class ErrorBodyLeakTest extends ApiTestBase {

    /** Distinctive enough that a substring assertion cannot pass by accident. */
    private static final String STORED = "clearance-omega-7f3a";

    private String token;

    @BeforeEach
    void seed() throws Exception {
        token = adminToken();
        mvc.perform(post("/v1/workspaces/ws").header("Authorization", bearer(token))).andExpect(status().isOk());
    }

    @Test
    void aWorkspaceRowThatWillNotParseIsNotHandedBack() throws Exception {
        execute("UPDATE workspaces SET metadata = '[\"" + STORED + "\"]'::jsonb WHERE name = 'ws'");

        mvc.perform(get("/v1/workspaces/ws").header("Authorization", bearer(token)))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("store_failed"))
                .andExpect(jsonPath("$.message", Matchers.not(Matchers.containsString(STORED))))
                .andExpect(content().string(Matchers.not(Matchers.containsString(STORED))));
    }

    /**
     * {@code internal_metadata} is on no response DTO. A 500 that quoted it was the only way a client
     * could observe it at all, which is why this case matters more than the workspace one above.
     */
    @Test
    void aColumnNoResponseCarriesIsNotHandedBackByTheErrorPath() throws Exception {
        mvc.perform(post("/v1/workspaces/ws/sessions/s").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        execute("UPDATE sessions SET internal_metadata = '[\"" + STORED + "\"]'::jsonb"
                + " WHERE workspace_name = 'ws' AND name = 's'");

        mvc.perform(get("/v1/workspaces/ws/sessions/s").header("Authorization", bearer(token)))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("store_failed"))
                .andExpect(content().string(Matchers.not(Matchers.containsString(STORED))));
    }

    /**
     * The body still says something. Falling all the way back to the catch-all's "the request could
     * not be completed" would lose the one fact worth telling a caller: retrying will not help, and
     * this is not their request's fault.
     */
    @Test
    void theCallerStillLearnsThatTheFailureIsInStoredData() throws Exception {
        execute("UPDATE workspaces SET configuration = '[1,2,3]'::jsonb WHERE name = 'ws'");

        mvc.perform(get("/v1/workspaces/ws").header("Authorization", bearer(token)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("a stored value could not be read; see the server log"));
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = PostgresSupport.dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
