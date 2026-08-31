package dev.dyad.llm.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dyad.llm.Json;
import dev.dyad.llm.LlmException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code test-fixtures/llm/{hash}.json}.
 *
 * <p>The directory is resolved once by walking up from the working directory to the repository root,
 * so every module's tests share one corpus regardless of which directory Gradle runs them in.
 */
public final class LlmFixtureStore {

    private static final String DIR_PROPERTY = "dyad.fixtures.dir";
    private static final String DIR_ENV = "DYAD_FIXTURE_DIR";

    private final Path directory;

    public LlmFixtureStore(Path directory) {
        this.directory = directory;
    }

    public static LlmFixtureStore fromEnvironment() {
        String configured = System.getProperty(DIR_PROPERTY, System.getenv(DIR_ENV));
        Path base = configured != null && !configured.isBlank() ? Paths.get(configured) : defaultDirectory();
        return new LlmFixtureStore(base.resolve("llm"));
    }

    private static Path defaultDirectory() {
        Path cursor = Paths.get("").toAbsolutePath();
        while (cursor != null) {
            if (Files.exists(cursor.resolve("settings.gradle.kts"))) {
                return cursor.resolve("test-fixtures");
            }
            cursor = cursor.getParent();
        }
        return Paths.get("test-fixtures").toAbsolutePath();
    }

    public Path directory() {
        return directory;
    }

    public Optional<LlmFixture> find(String key) {
        Path file = directory.resolve(key + ".json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode root = Json.read(Files.readString(file, StandardCharsets.UTF_8));
            List<String> chunks = null;
            JsonNode chunkNode = root.get("stream_chunks");
            if (chunkNode != null && chunkNode.isArray()) {
                chunks = new ArrayList<>();
                for (JsonNode c : chunkNode) {
                    chunks.add(c.asText());
                }
            }
            return Optional.of(
                    new LlmFixture(
                            key,
                            root.path("canonical_request").asText(),
                            root.hasNonNull("response") ? root.get("response").toString() : null,
                            chunks,
                            root.path("recorded_at").asText()));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read fixture " + file, e);
        }
    }

    public void write(String key, String canonicalRequest, String responseJson, List<String> streamChunks) {
        try {
            Files.createDirectories(directory);
            ObjectNode root = Json.object();
            root.put("key", key);
            root.put("recorded_at", Instant.now().toString());
            root.put("canonical_request", canonicalRequest);
            if (responseJson != null) {
                root.set("response", Json.read(responseJson));
            }
            if (streamChunks != null) {
                ArrayNode chunks = root.putArray("stream_chunks");
                streamChunks.forEach(chunks::add);
            }
            Files.writeString(
                    directory.resolve(key + ".json"), Json.writePretty(root) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LlmException("cannot write fixture " + key, e);
        }
    }
}
