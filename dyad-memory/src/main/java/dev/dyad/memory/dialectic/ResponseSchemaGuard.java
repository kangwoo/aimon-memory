package dev.dyad.memory.dialectic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dyad.core.DyadException;
import java.util.Set;

/**
 * Validates a caller-supplied response schema before it reaches a provider.
 *
 * <p>Callers can ask for structured answers, which means arbitrary JSON Schema arrives over HTTP.
 * That is an input, so it gets treated like one: a conservative subset, with hard caps on depth,
 * breadth and size. An unbounded schema is a denial-of-service primitive against both this process
 * and the provider's, and the exotic keywords buy nothing for a dialectic answer.
 */
public final class ResponseSchemaGuard {

    public static final int MAX_DEPTH = 5;
    public static final int MAX_PROPERTIES = 40;
    public static final int MAX_BYTES = 16_384;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_TYPES =
            Set.of("object", "array", "string", "integer", "number", "boolean");
    private static final Set<String> ALLOWED_KEYWORDS =
            Set.of("type", "properties", "items", "required", "additionalProperties", "enum", "description");

    private ResponseSchemaGuard() {}

    public static String validate(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            throw reject("schema is empty");
        }
        // Measured in bytes, as the name says. Counting characters let a schema written in Korean
        // through at roughly three times the intended size, because the cap exists to bound what is
        // parsed and forwarded, and that is a byte count.
        int bytes = schemaJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            throw reject("schema is " + bytes + " bytes, over the " + MAX_BYTES + " byte limit");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(schemaJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw reject("schema is not valid JSON");
        }
        walk(root, 0);
        return root.toString();
    }

    private static void walk(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) {
            throw reject("schema nests deeper than " + MAX_DEPTH + " levels");
        }
        if (!node.isObject()) {
            throw reject("every schema node must be an object");
        }
        node.fieldNames()
                .forEachRemaining(
                        field -> {
                            if (!ALLOWED_KEYWORDS.contains(field)) {
                                throw reject("unsupported schema keyword '" + field + "'");
                            }
                        });

        JsonNode type = node.get("type");
        if (type == null || !type.isTextual() || !ALLOWED_TYPES.contains(type.asText())) {
            throw reject("each node needs a 'type' from " + ALLOWED_TYPES);
        }
        JsonNode properties = node.get("properties");
        if (properties != null) {
            if (!properties.isObject()) {
                throw reject("'properties' must be an object");
            }
            if (properties.size() > MAX_PROPERTIES) {
                throw reject("an object may declare at most " + MAX_PROPERTIES + " properties");
            }
            properties.forEach(child -> walk(child, depth + 1));
        }
        JsonNode items = node.get("items");
        if (items != null) {
            walk(items, depth + 1);
        }
    }

    private static DyadException reject(String reason) {
        return new DyadException("bad_response_format", "response_format rejected: " + reason);
    }
}
