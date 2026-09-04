package dev.dyad.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Configuration is checked before it is stored.
 *
 * <p>The regression this closes was silent by construction: a weight vector that did not sum to 1.00
 * was accepted with a 200, echoed back in the response, and then dropped in favour of the defaults on
 * the next read. The only symptom was that tuning had no effect, which is indistinguishable from
 * tuning that did not help.
 */
class ConfigurationValidationTest extends ApiTestBase {

    private String token;

    @BeforeEach
    void seed() throws Exception {
        token = adminToken();
        mvc.perform(post("/v1/workspaces/ws").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions configure(String configuration)
            throws Exception {
        return mvc.perform(
                put("/v1/workspaces/ws/configuration")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configuration\":" + configuration + "}"));
    }

    @Test
    void aValidConfigurationIsStored() throws Exception {
        configure("{\"language\":\"ko\",\"recall.half_life_days\":30,\"recall.weights\":[0.4,0.3,0.1,0.1,0.05,0.05]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuration.language").value("ko"));
    }

    @Test
    void weightsThatDoNotSumToOneAreRejected() throws Exception {
        configure("{\"recall.weights\":[0.9,0.9,0.9,0.9,0.9,0.9]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("bad_configuration"));
    }

    @Test
    void theWrongNumberOfWeightsIsRejected() throws Exception {
        configure("{\"recall.weights\":[0.5,0.5]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("bad_configuration"));
    }

    /** A misspelled key used to store cleanly and change nothing, which is the worst of both. */
    @Test
    void anUnknownKeyIsRejectedRatherThanIgnored() throws Exception {
        configure("{\"recall.half_life\":30}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("bad_configuration"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("half_life")));
    }

    @Test
    void aValueOutsideTheRangeItCanBeHonouredInIsRejected() throws Exception {
        // A half-life of zero is a division; a threshold above one discards everything.
        configure("{\"recall.half_life_days\":0}").andExpect(status().isUnprocessableEntity());
        configure("{\"recall.threshold\":5}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aValueOfTheWrongTypeIsRejected() throws Exception {
        configure("{\"observe_me\":\"yes\"}").andExpect(status().isUnprocessableEntity());
        configure("{\"recall.oversample\":\"four\"}").andExpect(status().isUnprocessableEntity());
    }

    /** Zero turns a batch gate off, which is a configuration rather than a mistake. */
    @Test
    void turningBatchingOffIsAllowed() throws Exception {
        configure("{\"batch.idle_flush_seconds\":0}").andExpect(status().isOk());
    }

    /** The same check on the creation path: a workspace cannot be born with a configuration nothing reads. */
    @Test
    void creatingAWorkspaceWithABadConfigurationIsRejected() throws Exception {
        mvc.perform(
                        post("/v1/workspaces/other")
                                .header("Authorization", bearer(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"configuration\":{\"recall.weights\":[1,1,1,1,1,1]}}"))
                .andExpect(status().isUnprocessableEntity());
    }

    /**
     * Tuning is workspace configuration, and a peer is not a workspace.
     *
     * <p>The fix went onto the two workspace routes and stopped there, so the same silent drop
     * survived one level down: {@code PUT .../peers/alice/configuration} with a real tuning key
     * returned 200, echoed the value back, and changed nothing, because {@code
     * WorkspaceSettingsService} reads only the workspace's column. Checking at the write boundary is
     * what makes that impossible to forget on the next route.
     */
    @Test
    void tuningKeysOnAPeerAreRejectedRatherThanStoredAndIgnored() throws Exception {
        mvc.perform(post("/v1/workspaces/ws/peers/alice").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mvc.perform(
                        put("/v1/workspaces/ws/peers/alice/configuration")
                                .header("Authorization", bearer(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"configuration\":{\"recall.half_life_days\":5}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("bad_configuration"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("workspace")));
    }

    @Test
    void tuningKeysOnASessionAreRejectedToo() throws Exception {
        mvc.perform(
                        post("/v1/workspaces/ws/sessions/s1")
                                .header("Authorization", bearer(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"configuration\":{\"observe_others\":false}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("bad_configuration"));
    }

    /** Opaque client keys stay allowed there: nothing about them claims to tune anything. */
    @Test
    void aPeerMayStillCarryItsOwnOpaqueConfiguration() throws Exception {
        mvc.perform(
                        post("/v1/workspaces/ws/peers/bob")
                                .header("Authorization", bearer(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"configuration\":{\"client.avatar\":\"cat.png\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuration['client.avatar']").value("cat.png"));
    }
}
