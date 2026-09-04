package at.aimon.memory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Numbers that arrive from the network are capped.
 *
 * <p>They were not. {@code ?size=10000000} was an out-of-memory condition available to anyone holding
 * a token, and {@code recall(limit)} is worse than linear in that number because each signal path
 * oversamples by a multiple of it. The tool layer already clamped the arguments a <em>model</em>
 * composes, which made the trust relationship exactly backwards.
 */
class BoundsTest extends ApiTestBase {

    private String token;

    @BeforeEach
    void seed() throws Exception {
        token = adminToken();
        mvc.perform(post("/v1/workspaces/ws").header("Authorization", bearer(token))).andExpect(status().isOk());
        mvc.perform(post("/v1/workspaces/ws/sessions/s1").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    @Test
    void anAbsurdPageSizeIsClampedRatherThanHonoured() throws Exception {
        mvc.perform(get("/v1/workspaces").param("size", "10000000").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(Bounds.MAX_PAGE_SIZE));
    }

    @Test
    void aNegativeOrZeroPageSizeFallsBackToTheDefault() {
        assertThat(Bounds.size(0)).isEqualTo(50);
        assertThat(Bounds.size(-5)).isEqualTo(50);
        assertThat(Bounds.page(-1)).isZero();
        assertThat(Bounds.page(Integer.MAX_VALUE)).isEqualTo(Bounds.MAX_PAGE);
    }

    @Test
    void recallLimitIsCapped() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/v1/workspaces/ws/conclusions").contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", bearer(token))
                    .content("{\"observer\":\"a\",\"observed\":\"a\",\"session\":\"s1\","
                            + "\"content\":\"alice fact number " + i + "\",\"entities\":[]}"))
                    .andExpect(status().isOk());
        }

        mvc.perform(post("/v1/workspaces/ws/recall").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", bearer(token))
                .content("{\"query\":\"alice\",\"observer\":\"a\",\"observed\":\"a\"," + "\"limit\":100000000}"))
                .andExpect(status().isOk());

        assertThat(Bounds.recallLimit(100_000_000)).isEqualTo(Bounds.MAX_RECALL_LIMIT);
        assertThat(Bounds.recallLimit(null)).isEqualTo(10);
        assertThat(Bounds.recallLimit(0)).isEqualTo(10);
    }

    /** The cap lives in the request record too, so a caller that is not HTTP is covered as well. */
    @Test
    void theRecallRequestClampsItsOwnLimit() {
        var pair = new at.aimon.memory.core.key.PairKey("ws", "a", "a");
        assertThat(new at.aimon.memory.recall.RecallRequest(pair, "q", 100_000, null, null, false).limit())
                .isEqualTo(at.aimon.memory.recall.RecallRequest.MAX_LIMIT);
        assertThat(new at.aimon.memory.recall.RecallRequest(pair, "q", 0, null, null, false).limit())
                .isEqualTo(at.aimon.memory.recall.RecallRequest.DEFAULT_LIMIT);
    }

    @Test
    void auditHistoryAndDreamListingsAreCapped() {
        assertThat(Bounds.history(Integer.MAX_VALUE)).isEqualTo(Bounds.MAX_HISTORY);
        assertThat(Bounds.history(0)).isEqualTo(100);
    }
}
