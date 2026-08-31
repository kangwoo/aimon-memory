package dev.dyad.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import dev.dyad.core.key.PairKey;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The pair is the product. A token that reaches one it does not own defeats the only guarantee this
 * system makes.
 *
 * <p>Every pair-scoped route names its observer and observed in the body or the query string, where
 * {@code AuthInterceptor} — which sees path variables and nothing else — cannot check them. So the
 * route table said "peer scope required" and then let any peer token name any pair in its workspace.
 * The existing coverage missed it because it only ever exercised the path-variable case.
 */
class PairScopeTest extends ApiTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void seed() throws Exception {
        mvc.perform(post("/v1/workspaces/ws").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/workspaces/ws/peers/alice").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/workspaces/ws/peers/bob").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk());
    }

    @Test
    void aPeerTokenCannotRecallAnotherPeersMemory() throws Exception {
        mvc.perform(post("/v1/workspaces/ws/recall").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"salary\",\"observer\":\"alice\",\"observed\":\"carol\"}")
                        .header("Authorization", bearer(peerToken("ws", "bob"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));

        mvc.perform(post("/v1/workspaces/ws/recall").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"salary\",\"observer\":\"bob\",\"observed\":\"carol\"}")
                        .header("Authorization", bearer(peerToken("ws", "bob"))))
                .andExpect(status().isOk());
    }

    /** The observed side stays free: keeping a memory of someone is not a permission they grant. */
    @Test
    void aPeerTokenMayObserveAnyone() throws Exception {
        mvc.perform(get("/v1/workspaces/ws/conclusions")
                        .param("observer", "bob").param("observed", "alice")
                        .header("Authorization", bearer(peerToken("ws", "bob"))))
                .andExpect(status().isOk());
    }

    @Test
    void everyRouteThatNamesAPairChecksIt() throws Exception {
        String bob = bearer(peerToken("ws", "bob"));

        mvc.perform(get("/v1/workspaces/ws/conclusions")
                        .param("observer", "alice").param("observed", "alice").header("Authorization", bob))
                .andExpect(status().isForbidden());

        mvc.perform(get("/v1/workspaces/ws/recall/provenance")
                        .param("entity", "seoul").param("observer", "alice").param("observed", "alice")
                        .header("Authorization", bob))
                .andExpect(status().isForbidden());

        mvc.perform(get("/v1/workspaces/ws/peer-card")
                        .param("observer", "alice").param("observed", "alice").header("Authorization", bob))
                .andExpect(status().isForbidden());

        mvc.perform(post("/v1/workspaces/ws/peer-card/refresh")
                        .param("observer", "alice").param("observed", "alice").header("Authorization", bob))
                .andExpect(status().isForbidden());

        mvc.perform(post("/v1/workspaces/ws/conclusions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observer\":\"alice\",\"observed\":\"alice\",\"session\":\"s1\","
                                + "\"content\":\"alice owes 5000\"}")
                        .header("Authorization", bob))
                .andExpect(status().isForbidden());

        mvc.perform(post("/v1/workspaces/ws/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"what do you know\",\"observer\":\"alice\",\"observed\":\"alice\"}")
                        .header("Authorization", bob))
                .andExpect(status().isForbidden());
    }

    /** A conclusion belongs to one pair. Owning the pair you named is not owning the row you asked for. */
    @Test
    void theReasoningChainWillNotFetchAnotherPairsConclusion() throws Exception {
        String id = injectAliceConclusion();

        // bob names his own pair — which passes the scope check — but the id is alice's.
        mvc.perform(get("/v1/workspaces/ws/conclusions/" + id + "/chain")
                        .param("observer", "bob").param("observed", "bob")
                        .header("Authorization", bearer(peerToken("ws", "bob"))))
                .andExpect(status().isNotFound());

        mvc.perform(get("/v1/workspaces/ws/conclusions/" + id + "/chain")
                        .param("observer", "alice").param("observed", "alice")
                        .header("Authorization", bearer(peerToken("ws", "alice"))))
                .andExpect(status().isOk());
    }

    /**
     * These two routes name no pair at all — an id is the whole request — so the route table stopped
     * at "some peer token" and any peer could delete, or read the audit trail of, any conclusion in
     * its workspace. The pair comes off the row instead.
     */
    @Test
    void anIdAloneDoesNotReachAnotherPairsConclusion() throws Exception {
        String id = injectAliceConclusion();
        String bob = bearer(peerToken("ws", "bob"));

        mvc.perform(get("/v1/workspaces/ws/conclusions/" + id + "/events").header("Authorization", bob))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/v1/workspaces/ws/conclusions/" + id).header("Authorization", bob))
                .andExpect(status().isNotFound());

        // Still there: the refused delete must not have been a partial one.
        mvc.perform(get("/v1/workspaces/ws/conclusions/" + id + "/events")
                        .header("Authorization", bearer(peerToken("ws", "alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].event").value("add"));
        mvc.perform(delete("/v1/workspaces/ws/conclusions/" + id)
                        .header("Authorization", bearer(peerToken("ws", "alice"))))
                .andExpect(status().isOk());
    }

    /** Deleting a fact is exactly when its audit trail matters, so the row stays resolvable. */
    @Test
    void theOwnerCanStillReadTheAuditTrailAfterDeleting() throws Exception {
        String id = injectAliceConclusion();
        String alice = bearer(peerToken("ws", "alice"));

        mvc.perform(delete("/v1/workspaces/ws/conclusions/" + id).header("Authorization", alice))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/workspaces/ws/conclusions/" + id + "/events").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].event").value("delete"));
    }

    /** An id nobody owns is a 404, not an empty history that reads as "this fact has no events". */
    @Test
    void anUnknownConclusionIdIsNotFound() throws Exception {
        mvc.perform(get("/v1/workspaces/ws/conclusions/nope/events")
                        .header("Authorization", bearer(peerToken("ws", "alice"))))
                .andExpect(status().isNotFound());
    }

    private String injectAliceConclusion() throws Exception {
        String created =
                mvc.perform(post("/v1/workspaces/ws/conclusions").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"observer\":\"alice\",\"observed\":\"alice\",\"session\":\"s1\","
                                        + "\"content\":\"alice works at a bank\"}")
                                .header("Authorization", bearer(peerToken("ws", "alice"))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return MAPPER.readTree(created).path("id").asText();
    }

    /**
     * The gate that keeps this from regressing. A controller that builds its own {@link PairKey} has
     * skipped the check by construction, and the next route to grow an observer parameter is exactly
     * where that happens.
     */
    @Test
    void noControllerBuildsAPairKeyWithoutGoingThroughPairScope() {
        Path classes = repositoryRoot().resolve("dyad-api/build/classes/java/main");
        org.assertj.core.api.Assertions.assertThat(Files.isDirectory(classes))
                .as("compiled API classes found; run ./gradlew classes if this fails")
                .isTrue();

        JavaClasses imported = new ClassFileImporter().importPath(classes);
        noClasses()
                .that()
                .resideInAPackage("dev.dyad.api.web..")
                .should()
                .callConstructor(PairKey.class, String.class, String.class, String.class)
                .as("controllers must obtain a PairKey from PairScope, which checks it against the token")
                .check(imported);
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null && !Files.exists(cursor.resolve("settings.gradle.kts"))) {
            cursor = cursor.getParent();
        }
        return cursor == null ? Path.of("").toAbsolutePath() : cursor;
    }
}
