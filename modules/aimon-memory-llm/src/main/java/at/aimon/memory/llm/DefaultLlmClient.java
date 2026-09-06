package at.aimon.memory.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.memory.core.MemoryException;
import at.aimon.memory.core.spi.LlmClient;
import at.aimon.memory.core.spi.llm.LlmMessage;
import at.aimon.memory.core.spi.llm.LlmRequest;
import at.aimon.memory.core.spi.llm.LlmUsage;
import at.aimon.memory.core.spi.llm.ResponseFormat;
import at.aimon.memory.core.spi.llm.Role;
import at.aimon.memory.core.spi.llm.StructuredResult;
import at.aimon.memory.core.spi.llm.ToolCall;
import at.aimon.memory.core.spi.llm.ToolDef;
import at.aimon.memory.core.spi.llm.ToolLoopResult;
import at.aimon.memory.llm.backend.ChatBackend;
import at.aimon.memory.llm.backend.ChatCall;
import at.aimon.memory.llm.backend.ChatResponse;
import at.aimon.memory.llm.backend.ChatTurn;
import at.aimon.memory.llm.backend.ToolResult;
import at.aimon.memory.llm.backend.ToolSpec;
import at.aimon.memory.llm.backend.ToolUse;

/**
 * The provider-neutral half of the LLM layer: structured extraction, the agentic loop, streaming.
 *
 * <p>Written once against {@link ChatBackend} so that adding a provider means writing a translator,
 * not another loop. The loop in particular is where the subtle bugs live — dropping a tool result,
 * losing the assistant turn that requested it, running forever — and there should only be one of it.
 */
public final class DefaultLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultLlmClient.class);

    /** Tool output beyond this is cut. A grep that matches everything must not eat the context window. */
    public static final int MAX_TOOL_OUTPUT_CHARS = 8_000;

    private final ChatBackend backend;
    private final int maxToolOutputChars;

    public DefaultLlmClient(ChatBackend backend) {
        this(backend, MAX_TOOL_OUTPUT_CHARS);
    }

    public DefaultLlmClient(ChatBackend backend, int maxToolOutputChars) {
        this.backend = backend;
        this.maxToolOutputChars = maxToolOutputChars;
    }

    @Override
    public <T> StructuredResult<T> structured(LlmRequest req, Class<T> schema) {
        ResponseFormat format = req.responseFormat() != null
                ? req.responseFormat()
                : ResponseFormat.strict(schemaName(schema), JsonSchemas.forRecord(schema));
        ChatResponse response = backend.chat(call(req, format, List.of()));
        String json = response.text();
        if (json == null || json.isBlank()) {
            throw new LlmException("empty_structured_response", "model returned no content for " + format.name());
        }
        return new StructuredResult<>(Json.read(json, schema), json, response.model(), response.usage());
    }

    @Override
    public ToolLoopResult toolLoop(LlmRequest req, List<ToolDef> tools, int maxIterations) {
        Map<String, ToolDef> byName = new LinkedHashMap<>();
        List<ToolSpec> specs = new ArrayList<>(tools.size());
        for (ToolDef tool : tools) {
            byName.put(tool.name(), tool);
            specs.add(new ToolSpec(tool.name(), tool.description(), tool.parametersSchema()));
        }

        List<ChatTurn> turns = new ArrayList<>(toTurns(req.messages()));
        List<ToolCall> record = new ArrayList<>();
        LlmUsage usage = LlmUsage.NONE;
        String lastText = null;

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            ChatResponse response = backend.chat(new ChatCall(req.model(), req.system(), turns, specs,
                    req.responseFormat(), req.temperature(), req.maxTokens()));
            usage = usage.plus(response.usage());
            if (response.text() != null && !response.text().isBlank()) {
                lastText = response.text();
            }
            if (!response.wantsTools()) {
                return new ToolLoopResult(response.text(), iteration, record, usage, false);
            }

            turns.add(new ChatTurn.AssistantToolUse(response.text(), response.toolUses()));
            List<ToolResult> results = new ArrayList<>(response.toolUses().size());
            for (ToolUse use : response.toolUses()) {
                ToolDef tool = byName.get(use.name());
                String output;
                boolean failed = false;
                if (tool == null) {
                    output = "No tool named '" + use.name() + "'.";
                    failed = true;
                } else {
                    try {
                        output = truncate(tool.handler().invoke(use.argumentsJson()));
                    } catch (RuntimeException e) {
                        // A failing tool is data for the model, not a crash: it can retry with different
                        // arguments or say it could not find out. Aborting the loop throws away the work.
                        //
                        // `publicMessageOf`, because whatever goes in here is on its way to a caller by
                        // the longest route in the system: the model reads it, and the model's answer is
                        // the 200 body of `POST /chat`. The tools are recall and search, so this build's
                        // own `MemoryException`s reach it — a rejected filter, a missing entity — and
                        // those are worded for whoever asked and still arrive whole, which is what lets
                        // the model retry with different arguments. What does not is the rest of a
                        // `catch (RuntimeException)`: a `DataAccessException` out of the same store
                        // carries the statement, the relation and, on a not-null or check violation, the
                        // failing row. Handing that to a model and asking it to explain itself is asking
                        // it to quote it.
                        //
                        // Logged here because nothing above logs it. `ToolLoopResult` records the output
                        // for the transcript, `Dtos.ToolCallResponse` deliberately omits it, and the
                        // exception is swallowed on purpose — so without this line the summary would be
                        // the only trace left of a tool that is failing every call.
                        log.warn("tool '{}' failed", use.name(), e);
                        output = "Tool failed: " + MemoryException.publicMessageOf(e);
                        failed = true;
                    }
                }
                results.add(new ToolResult(use.id(), use.name(), output, failed));
                record.add(new ToolCall(use.name(), use.argumentsJson(), output, failed));
            }
            turns.add(new ChatTurn.ToolResults(results));
        }
        return new ToolLoopResult(lastText, maxIterations, record, usage, true);
    }

    @Override
    public Stream<String> stream(LlmRequest req) {
        return backend.stream(call(req, req.responseFormat(), List.of()));
    }

    private ChatCall call(LlmRequest req, ResponseFormat format, List<ToolSpec> tools) {
        return new ChatCall(req.model(), req.system(), toTurns(req.messages()), tools, format, req.temperature(),
                req.maxTokens());
    }

    private static List<ChatTurn> toTurns(List<LlmMessage> messages) {
        List<ChatTurn> turns = new ArrayList<>(messages.size());
        for (LlmMessage message : messages) {
            turns.add(message.role() == Role.USER
                    ? new ChatTurn.UserText(message.content())
                    : new ChatTurn.AssistantText(message.content()));
        }
        return turns;
    }

    private String truncate(String output) {
        if (output == null) {
            return "";
        }
        if (output.length() <= maxToolOutputChars) {
            return output;
        }
        return output.substring(0, maxToolOutputChars) + "\n… truncated at " + maxToolOutputChars
                + " characters; narrow the query to see the rest.";
    }

    private static String schemaName(Class<?> type) {
        return type.getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }
}
