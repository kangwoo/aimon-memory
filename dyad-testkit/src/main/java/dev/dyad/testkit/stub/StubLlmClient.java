package dev.dyad.testkit.stub;

import dev.dyad.core.DyadException;
import dev.dyad.core.spi.LlmClient;
import dev.dyad.core.spi.llm.LlmRequest;
import dev.dyad.core.spi.llm.LlmUsage;
import dev.dyad.core.spi.llm.StructuredResult;
import dev.dyad.core.spi.llm.ToolDef;
import dev.dyad.core.spi.llm.ToolLoopResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A scripted model.
 *
 * <p>This is what lets the whole ingestion pipeline be tested end to end before a single real prompt
 * exists. The responder is a function of the request rather than a fixed queue, so a test can react
 * to what was actually asked instead of assuming call order.
 */
public final class StubLlmClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Function<LlmRequest, String> responder;
    private final List<LlmRequest> requests = new ArrayList<>();
    private String toolLoopAnswer = "stub answer";

    public StubLlmClient(Function<LlmRequest, String> responder) {
        this.responder = responder;
    }

    /** Always answers with the same JSON, whatever it was asked. */
    public static StubLlmClient returning(String json) {
        return new StubLlmClient(request -> json);
    }

    public StubLlmClient withToolLoopAnswer(String answer) {
        this.toolLoopAnswer = answer;
        return this;
    }

    @Override
    public <T> StructuredResult<T> structured(LlmRequest req, Class<T> schema) {
        requests.add(req);
        String json = responder.apply(req);
        try {
            return new StructuredResult<>(MAPPER.readValue(json, schema), json, "stub", new LlmUsage(10, 10));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new DyadException("stub_bad_json", "stub response is not valid " + schema.getSimpleName() + ": " + json);
        }
    }

    @Override
    public ToolLoopResult toolLoop(LlmRequest req, List<ToolDef> tools, int maxIterations) {
        requests.add(req);
        return new ToolLoopResult(toolLoopAnswer, 1, List.of(), new LlmUsage(10, 10), false);
    }

    @Override
    public Stream<String> stream(LlmRequest req) {
        requests.add(req);
        return Stream.of(toolLoopAnswer);
    }

    public List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    public LlmRequest lastRequest() {
        if (requests.isEmpty()) {
            throw new IllegalStateException("the stub was never called");
        }
        return requests.get(requests.size() - 1);
    }
}
