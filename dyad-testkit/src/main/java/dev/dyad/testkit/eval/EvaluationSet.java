package dev.dyad.testkit.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A labelled corpus and the queries asked of it.
 *
 * <p>Held as data rather than code so that adding a judgement is a one-line diff a non-programmer can
 * read and argue with. Arguing with the judgements is the point — they are the only part of the
 * ranking story that encodes what a person actually wanted.
 */
public record EvaluationSet(String name, String note, List<Document> corpus, List<Query> queries) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param timesDerived seeds the {@code reinf} signal
     * @param daysAgo how long since the last reinforcement, seeding {@code rec}
     */
    public record Document(
            String id,
            String content,
            String level,
            List<String> entities,
            int timesDerived,
            int daysAgo,
            List<String> sourceIds,
            Double confidence) {}

    /** @param judgements document id to graded relevance: 3 answers it, 2 strong, 1 related, 0 not */
    public record Query(String id, String query, Map<String, Integer> judgements) {}

    public static EvaluationSet load(String name) {
        Path file = directory().resolve(name + ".json");
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("no evaluation set at " + file);
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            List<Document> corpus = new ArrayList<>();
            for (JsonNode node : root.path("corpus")) {
                corpus.add(
                        new Document(
                                node.path("id").asText(),
                                node.path("content").asText(),
                                node.path("level").asText("explicit"),
                                strings(node.path("entities")),
                                node.path("timesDerived").asInt(1),
                                node.path("daysAgo").asInt(0),
                                strings(node.path("sourceIds")),
                                node.hasNonNull("confidence") ? node.get("confidence").asDouble() : null));
            }
            List<Query> queries = new ArrayList<>();
            for (JsonNode node : root.path("queries")) {
                Map<String, Integer> judgements = new LinkedHashMap<>();
                node.path("judgements")
                        .fields()
                        .forEachRemaining(e -> judgements.put(e.getKey(), e.getValue().asInt()));
                queries.add(new Query(node.path("id").asText(), node.path("query").asText(), judgements));
            }
            return new EvaluationSet(name, root.path("note").asText(""), corpus, queries);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read evaluation set " + file, e);
        }
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.forEach(item -> out.add(item.asText()));
        return out;
    }

    public static Path directory() {
        String configured = System.getProperty("dyad.fixtures.dir");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured).resolve("eval");
        }
        Path cursor = Paths.get("").toAbsolutePath();
        while (cursor != null && !Files.exists(cursor.resolve("settings.gradle.kts"))) {
            cursor = cursor.getParent();
        }
        return (cursor == null ? Paths.get("").toAbsolutePath() : cursor)
                .resolve("test-fixtures")
                .resolve("eval");
    }

    /**
     * A stable digest of everything the metrics are computed over.
     *
     * <p>The baseline is a floor for one corpus and one set of judgements, and it says so nowhere. So a
     * change that grew the corpus from 26 documents to 40 and the queries from 16 to 50 could
     * regenerate the baseline in the same commit as a change to the {@code ent} signal, and the gate
     * would pass either way — several per-query scores fell against the old numbers and were frozen as
     * the new floor with nothing to say whether the corpus or the ranking had moved them. Recording
     * this in the baseline is what makes that visible: the corpus can still change, but not silently
     * and not in the same breath as the behaviour being measured.
     *
     * <p>Judgements are sorted, because a reordered map is not a different evaluation set.
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        for (Document document : corpus) {
            sb.append(document.id()).append('\u001f').append(document.content()).append('\u001f')
                    .append(document.level()).append('\u001f').append(document.entities()).append('\u001f')
                    .append(document.timesDerived()).append('\u001f').append(document.daysAgo()).append('\u001f')
                    .append(document.sourceIds()).append('\u001f').append(document.confidence()).append('\u001e');
        }
        for (Query query : queries) {
            sb.append(query.id()).append('\u001f').append(query.query()).append('\u001f');
            query.judgements().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue()).append(','));
            sb.append('\u001e');
        }
        try {
            byte[] digest =
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Every judgement must name a document that exists, or the metrics silently measure nothing. */
    public void validate() {
        List<String> ids = corpus.stream().map(Document::id).toList();
        for (Query query : queries) {
            for (String judged : query.judgements().keySet()) {
                if (!ids.contains(judged)) {
                    throw new IllegalStateException(
                            "query '" + query.id() + "' judges unknown document '" + judged + "'");
                }
            }
        }
    }
}
