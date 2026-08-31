package dev.dyad.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonSchemasTest {

    record Inner(String name, int count) {}

    record Outer(String title, List<String> tags, List<Inner> items, boolean active, double score) {}

    record Unsupported(java.util.Map<String, String> nope) {}

    @Test
    void generatesStrictSchemaFromARecord() {
        JsonNode schema = Json.read(JsonSchemas.forRecord(Outer.class));

        assertThat(schema.path("type").asText()).isEqualTo("object");
        // Strict mode requires additionalProperties:false and every property named in required.
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("required"))
                .extracting(node -> node.toString())
                .asString()
                .contains("title", "tags", "items", "active", "score");

        assertThat(schema.path("properties").path("tags").path("type").asText()).isEqualTo("array");
        assertThat(schema.path("properties").path("tags").path("items").path("type").asText())
                .isEqualTo("string");
        assertThat(schema.path("properties").path("items").path("items").path("properties")
                        .path("count").path("type").asText())
                .isEqualTo("integer");
        assertThat(schema.path("properties").path("score").path("type").asText()).isEqualTo("number");
        assertThat(schema.path("properties").path("active").path("type").asText()).isEqualTo("boolean");
    }

    /**
     * Refusing an unsupported type beats emitting a permissive schema: a schema that accepts more than
     * intended moves the failure from generation time to parse time, far from the cause.
     */
    @Test
    void refusesTypesItCannotExpressPrecisely() {
        assertThatThrownBy(() -> JsonSchemas.forRecord(Unsupported.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("unsupported schema type");

        assertThatThrownBy(() -> JsonSchemas.forRecord(String.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("is not a record");
    }
}
