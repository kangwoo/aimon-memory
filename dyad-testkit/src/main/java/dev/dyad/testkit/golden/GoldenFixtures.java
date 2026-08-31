package dev.dyad.testkit.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Loads and writes golden fixtures under {@code test-fixtures/golden}.
 *
 * <p>Setting {@code dyad.golden.update=true} rewrites them instead of asserting. That switch is a
 * convenience with a sharp edge: a rewrite makes every test pass by definition, so a diff that
 * touches these files has to be read as carefully as the code that produced it.
 */
public final class GoldenFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UPDATE_PROPERTY = "dyad.golden.update";

    private final Path directory;

    public GoldenFixtures(Path directory) {
        this.directory = directory;
    }

    public static GoldenFixtures standard() {
        return new GoldenFixtures(repositoryRoot().resolve("test-fixtures").resolve("golden"));
    }

    public static boolean updateMode() {
        return Boolean.parseBoolean(System.getProperty(UPDATE_PROPERTY, "false"));
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("dyad.fixtures.dir");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured).getParent();
        }
        Path cursor = Paths.get("").toAbsolutePath();
        while (cursor != null) {
            if (Files.exists(cursor.resolve("settings.gradle.kts"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return Paths.get("").toAbsolutePath();
    }

    public boolean exists(String name) {
        return Files.isRegularFile(path(name));
    }

    public JsonNode load(String name) {
        Path file = path(name);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(
                    "missing golden fixture " + file + " (run with -Ddyad.golden.update=true to create it)");
        }
        try {
            return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read golden fixture " + file, e);
        }
    }

    public void write(String name, Object value) {
        Path file = path(name);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write golden fixture " + file, e);
        }
    }

    public JsonNode toNode(Object value) {
        return MAPPER.valueToTree(value);
    }

    private Path path(String name) {
        return directory.resolve(name + ".json");
    }
}
