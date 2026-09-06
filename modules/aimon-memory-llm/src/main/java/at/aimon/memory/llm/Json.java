package at.aimon.memory.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** One mapper for the module, configured once. */
public final class Json {

    private static final Logger log = LoggerFactory.getLogger(Json.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw unparseable("JSON", json, e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw unparseable("JSON as " + type.getSimpleName(), json, e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new LlmException("bad_json", "could not serialise " + value.getClass().getSimpleName());
        }
    }

    public static String writePretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new LlmException("bad_json", "could not serialise " + value.getClass().getSimpleName());
        }
    }

    /**
     * The text that would not parse goes to the log, never to the caller.
     *
     * <p>Almost everything that reaches here is a provider's or a model's own output: the body of an
     * LLM response, a streamed chunk, the structured answer {@code DefaultLlmClient} deserialises. The
     * caller did not send it, and a model's answer is written from a prompt built out of the
     * workspace's stored conclusions and messages — so quoting 200 characters of it into a
     * {@code bad_json} 500 could hand back memory that this request never mentioned.
     *
     * <p>{@code what} still names the target type. That is a compile-time fact about this build, not
     * data, and it is the one part of the old message worth keeping on the wire: it tells a caller
     * whether the model returned nothing usable or returned the wrong shape.
     */
    private static LlmException unparseable(String what, String json, JsonProcessingException cause) {
        log.warn("could not parse {}: {}", what, json, cause);
        return new LlmException("bad_json", "could not parse " + what + "; see the server log");
    }
}
