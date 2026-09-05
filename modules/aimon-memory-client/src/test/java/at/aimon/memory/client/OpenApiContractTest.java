package at.aimon.memory.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The wire format this adapter assumes, checked against the description the service publishes.
 *
 * <p>The same format was written out by hand in three places and only two of them were tied
 * together. {@code Dtos} in {@code aimon-memory-api} is the truth, {@code docs/openapi.json} is
 * generated from it and {@code OpenApiSpecTest} fails when the committed copy drifts — and then this
 * module parsed the JSON with string literals that nothing compared to either. Renaming a field on
 * {@code RecallHitResponse} would update the DTO, update the description, keep both gates green, and
 * leave {@code RemotePeerMemory} reading {@code score} out of an object that no longer has one:
 * {@code JsonNode.path} answers a missing field with a zero rather than an error, so the adapter
 * would have gone on returning hits scored 0.0. {@code RemotePeerMemoryWireTest} could not catch it
 * either, because the server it runs against is a stub in that same file, written from the same
 * assumption.
 *
 * <p>So this reads the committed description and asserts that every field the adapter names is in
 * it. It is deliberately not a schema validator: what needs pinning is the intersection — the names
 * this client depends on — and a field the client ignores is free to change.
 *
 * <p>It lives in {@code src/test} rather than in the {@code contractTest} source set on purpose.
 * That tier skips itself when aimon-core's testkit does not resolve; this needs one committed file
 * and must never skip. And it keeps the property the module was built for
 * ({@code build.gradle.kts}, top): {@code aimon-memory-client} still does not depend on
 * {@code aimon-memory-api}, so an application taking the adapter does not take pgvector, Flyway,
 * Lucene and a Spring Boot application with it.
 */
class OpenApiContractTest {

    /** Written by {@code aimon.java-conventions}, so this works wherever Gradle put the working directory. */
    private static final Path DESCRIPTION = Path.of(System.getProperty("aimon.memory.docs.dir", "docs"))
            .resolve("openapi.json");

    private static JsonNode openapi;

    @BeforeAll
    static void readTheCommittedDescription() throws IOException {
        assertThat(DESCRIPTION).as("the generated API description; :aimon-memory-api:test writes it").exists();
        openapi = new ObjectMapper().readTree(Files.readString(DESCRIPTION));
    }

    /** The five tiers of {@code PeerMemory}, as this adapter routes them. Mirrors ADR 0007's table. */
    @Test
    void everyTierHasTheRouteTheAdapterCalls() {
        assertRoute("SNAPSHOT", "/v1/workspaces/{workspace}/conclusions", "get");
        assertRoute("SEARCH", "/v1/workspaces/{workspace}/recall", "post");
        assertRoute("CHAT", "/v1/workspaces/{workspace}/chat", "post");
        assertRoute("OBSERVE", "/v1/workspaces/{workspace}/conclusions", "post");
        assertRoute("INGEST", "/v1/workspaces/{workspace}/sessions/{session}/messages", "post");
    }

    /** Query parameters {@code RemoteSnapshotReader} sends on the snapshot read. */
    @Test
    void theSnapshotReadSendsParametersTheRouteDeclares() {
        List<String> declared = new ArrayList<>();
        openapi.path("paths").path("/v1/workspaces/{workspace}/conclusions").path("get").path("parameters")
                .forEach(parameter -> declared.add(parameter.path("name").asText()));
        assertThat(declared).contains("observer", "observed", "page", "size");
    }

    /**
     * Fields the adapter reads out of a response. Each entry is a schema and the properties
     * {@code RemotePeerMemory} names on it.
     */
    @Test
    void everyResponseFieldTheAdapterReadsExists() {
        Map<String, List<String>> reads = Map.of("PageResponseConclusionResponse", List.of("items", "hasNext"),
                "ConclusionResponse",
                List.of("id", "observer", "observed", "content", "level", "messageIds", "createdAt", "confidence",
                        "timesDerived", "lastReinforcedAt", "session"),
                "RecallResponseBody", List.of("hits"), "RecallHitResponse", List.of("conclusion", "score", "explain"),
                // The six signals, read by name in signalsOf(...) and handed to aimon-core as a map.
                "ExplainResponse", List.of("sem", "kw", "ent", "reinf", "rec", "lvl"), "ChatResponse",
                List.of("answer"));
        reads.forEach(this::assertProperties);
    }

    /** Fields the adapter writes into a request body. */
    @Test
    void everyRequestFieldTheAdapterSendsExists() {
        Map<String, List<String>> writes = Map.of("RecallQuery",
                List.of("observer", "observed", "query", "limit", "threshold", "explain"), "CreateConclusion",
                List.of("observer", "observed", "content", "session"), "ChatRequest",
                List.of("observer", "observed", "question", "reasoningLevel"), "CreateMessages", List.of("messages"),
                "NewMessage", List.of("peer", "content"));
        writes.forEach(this::assertProperties);
    }

    private void assertRoute(String tier, String path, String method) {
        assertThat(openapi.path("paths").path(path).has(method))
                .as("%s: %s %s is missing from the published description", tier, method.toUpperCase(), path).isTrue();
    }

    private void assertProperties(String schema, List<String> properties) {
        JsonNode node = openapi.path("components").path("schemas").path(schema);
        assertThat(node.isObject()).as("schema '%s' is missing from the published description", schema).isTrue();
        List<String> declared = new ArrayList<>();
        node.path("properties").fieldNames().forEachRemaining(declared::add);
        assertThat(declared).as("RemotePeerMemory names these on '%s'; a rename here reaches the adapter as a "
                + "silently absent field, not as an error", schema).containsAll(properties);
    }
}
