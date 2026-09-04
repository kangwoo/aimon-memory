package at.aimon.memory.engine.dialectic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.MemoryException;

/**
 * The response schema arrives over HTTP from a caller, so it is an input and gets treated like one.
 * An unbounded schema is a denial-of-service primitive against this process and the provider's.
 */
class ResponseSchemaGuardTest {

    @Test
    void acceptsTheSupportedSubset() {
        String schema = """
                {"type":"object","properties":{
                  "answer":{"type":"string","description":"the answer"},
                  "confidence":{"type":"number"},
                  "sources":{"type":"array","items":{"type":"string"}},
                  "verdict":{"type":"string","enum":["yes","no"]}},
                 "required":["answer"],"additionalProperties":false}
                """;
        assertThat(ResponseSchemaGuard.validate(schema)).contains("answer");
    }

    @Test
    void rejectsUnboundedNesting() {
        StringBuilder deep = new StringBuilder("{\"type\":\"array\",\"items\":");
        for (int i = 0; i < 12; i++) {
            deep.append("{\"type\":\"array\",\"items\":");
        }
        deep.append("{\"type\":\"string\"}");
        deep.append("}".repeat(13));

        assertThatThrownBy(() -> ResponseSchemaGuard.validate(deep.toString())).isInstanceOf(MemoryException.class)
                .hasMessageContaining("nests deeper than");
    }

    /** The cap is in bytes, because bytes are what gets parsed and forwarded. */
    @Test
    void theSizeCapIsMeasuredInBytesNotCharacters() {
        // Under the limit as characters, over it as UTF-8 — which is how a Korean schema slipped past.
        String korean = "가".repeat(8_000);
        assertThat(korean.length()).isLessThan(ResponseSchemaGuard.MAX_BYTES);
        assertThat(korean.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isGreaterThan(ResponseSchemaGuard.MAX_BYTES);

        String schema = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"description\":\"" + korean
                + "\"}}}";
        assertThatThrownBy(() -> ResponseSchemaGuard.validate(schema)).isInstanceOf(MemoryException.class)
                .hasMessageContaining("byte limit");
    }

    @Test
    void rejectsOversizedAndOverwideSchemas() {
        assertThatThrownBy(() -> ResponseSchemaGuard.validate("{\"type\":\"object\"} " + " ".repeat(20_000)))
                .isInstanceOf(MemoryException.class).hasMessageContaining("byte limit");

        StringBuilder wide = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        for (int i = 0; i < 60; i++) {
            wide.append("\"p").append(i).append("\":{\"type\":\"string\"}");
            if (i < 59) {
                wide.append(',');
            }
        }
        wide.append("}}");
        assertThatThrownBy(() -> ResponseSchemaGuard.validate(wide.toString())).isInstanceOf(MemoryException.class)
                .hasMessageContaining("at most");
    }

    /** Exotic keywords buy nothing for a dialectic answer and widen the attack surface. */
    @Test
    void rejectsKeywordsOutsideTheSubset() {
        assertThatThrownBy(() -> ResponseSchemaGuard
                .validate("{\"type\":\"object\",\"patternProperties\":{\".*\":{\"type\":\"string\"}}}"))
                .isInstanceOf(MemoryException.class).hasMessageContaining("unsupported schema keyword");

        assertThatThrownBy(() -> ResponseSchemaGuard.validate("{\"type\":\"null\"}"))
                .isInstanceOf(MemoryException.class).hasMessageContaining("needs a 'type'");

        assertThatThrownBy(() -> ResponseSchemaGuard.validate("not json")).isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> ResponseSchemaGuard.validate("")).isInstanceOf(MemoryException.class);
    }

    @Test
    void reasoningLevelsScaleToolsAndIterationsTogether() {
        assertThat(ReasoningLevel.MINIMAL.toolNames()).containsExactly(ToolRegistry.RECALL);
        assertThat(ReasoningLevel.MINIMAL.maxIterations()).isEqualTo(1);
        assertThat(ReasoningLevel.MAX.toolNames()).hasSize(7);

        // Both dimensions have to scale together: a cheap question with an expensive toolset spends
        // its whole budget exploring.
        for (ReasoningLevel level : ReasoningLevel.values()) {
            assertThat(level.maxIterations()).isGreaterThanOrEqualTo(level.toolNames().size() - 1);
        }
        assertThat(ReasoningLevel.fromWire(null)).isEqualTo(ReasoningLevel.MEDIUM);
        assertThat(ReasoningLevel.fromWire("HIGH")).isEqualTo(ReasoningLevel.HIGH);
        assertThatThrownBy(() -> ReasoningLevel.fromWire("extreme")).isInstanceOf(MemoryException.class);
    }
}
