package at.aimon.memory.llm;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** One mapper for the module, configured once. */
public final class Json {

    private static final Logger log = LoggerFactory.getLogger(Json.class);

    // FAIL_ON_UNKNOWN_PROPERTIES is off by default in Jackson 3, so this line no longer changes
    // behaviour — it states it. A provider adding a field to a response must not break parsing here,
    // and that is a property of this mapper worth reading off the mapper rather than off a release note.
    //
    // Two other defaults flipped under this mapper when the build moved to Jackson 3, and they are
    // left flipped deliberately. FAIL_ON_TRAILING_TOKENS is now ON: `{"a":1} and here is why` used to
    // parse, silently discarding the prose, and now raises. A model that appends a sentence to its
    // JSON has not answered the schema it was given, and reading half of it is how that went
    // unnoticed. FAIL_ON_NULL_FOR_PRIMITIVES is now ON too: a `null` for a record's `int` component
    // raises rather than binding 0. No structured-output record here uses a primitive component today
    // — every one is boxed — but `JsonSchemas` will emit a schema for one, so this is written down
    // before it is reached rather than after.
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

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
        } catch (JacksonException e) {
            throw unparseable("JSON", json, e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException e) {
            throw unparseable("JSON as " + type.getSimpleName(), json, e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new LlmException("bad_json", "could not serialise " + value.getClass().getSimpleName());
        }
    }

    public static String writePretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JacksonException e) {
            throw new LlmException("bad_json", "could not serialise " + value.getClass().getSimpleName());
        }
    }

    /**
     * Runs a reader over JSON someone else produced, turning Jackson's own failures into this
     * module's.
     *
     * <p>Jackson 3 redefined the {@code asString}/{@code asInt}/{@code asBoolean} family from
     * "coerce, falling back to a zero value" to "coerce, or raise". {@code asString()} on an object
     * node returned {@code ""} under Jackson 2 and now throws {@code JsonNodeException};
     * {@code asInt()} on {@code "n/a"} returned {@code 0} and now throws. That strictness is wanted —
     * a provider answering in the wrong shape should not be read as an empty answer — but it arrives
     * as a {@code RuntimeException} that is not an {@code LlmException}, and that combination is the
     * dangerous part.
     *
     * <p>{@code FallbackChatBackend.run} catches {@code LlmException} and nothing else, so an
     * unwrapped {@code JsonNodeException} walks past the failover loop entirely: one provider
     * answering in a shape this code cannot read would take the request down instead of handing it to
     * the next provider. Further up it misses {@code ApiExceptionHandler}'s {@code MemoryException}
     * branch too and lands in the catch-all as a 500 with no stable code. Wrapping here is what puts
     * a misshapen response back on the path this module already has for one.
     *
     * <p>{@code bad_json} rather than a code of its own, and deliberately not {@code llm_rejected}:
     * the fallback chain treats every other code as worth another attempt, which is what should
     * happen when one provider of several cannot be read.
     */
    public static <T> T shaped(String source, Supplier<T> reader) {
        try {
            return reader.get();
        } catch (JacksonException e) {
            log.warn("could not read {}'s response shape", source, e);
            throw new LlmException("bad_json", "could not read " + source + "'s response; see the server log");
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
    private static LlmException unparseable(String what, String json, JacksonException cause) {
        log.warn("could not parse {}: {}", what, json, cause);
        return new LlmException("bad_json", "could not parse " + what + "; see the server log");
    }
}
