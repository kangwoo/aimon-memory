package dev.dyad.core.spi;

import dev.dyad.core.spi.llm.LlmRequest;
import dev.dyad.core.spi.llm.StructuredResult;
import dev.dyad.core.spi.llm.ToolDef;
import dev.dyad.core.spi.llm.ToolLoopResult;
import java.util.List;
import java.util.stream.Stream;

/**
 * The only way Dyad talks to a model.
 *
 * <p>Deliberately three methods, not a general chat surface: structured extraction, an agentic loop,
 * and streaming. Anything the pipeline needs has to fit one of the three, which is what keeps the
 * record/replay harness able to cover every LLM path.
 */
public interface LlmClient {

    <T> StructuredResult<T> structured(LlmRequest req, Class<T> schema);

    ToolLoopResult toolLoop(LlmRequest req, List<ToolDef> tools, int maxIterations);

    Stream<String> stream(LlmRequest req);
}
