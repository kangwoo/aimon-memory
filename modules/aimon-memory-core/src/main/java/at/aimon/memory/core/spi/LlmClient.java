package at.aimon.memory.core.spi;

import java.util.List;
import java.util.stream.Stream;

import at.aimon.memory.core.spi.llm.LlmRequest;
import at.aimon.memory.core.spi.llm.StructuredResult;
import at.aimon.memory.core.spi.llm.ToolDef;
import at.aimon.memory.core.spi.llm.ToolLoopResult;

/**
 * The only way aimon-memory talks to a model.
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
