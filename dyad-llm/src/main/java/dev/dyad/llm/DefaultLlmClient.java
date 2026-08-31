package dev.dyad.llm;

import dev.dyad.core.spi.LlmClient;
import dev.dyad.core.spi.llm.LlmMessage;
import dev.dyad.core.spi.llm.LlmRequest;
import dev.dyad.core.spi.llm.LlmUsage;
import dev.dyad.core.spi.llm.ResponseFormat;
import dev.dyad.core.spi.llm.Role;
import dev.dyad.core.spi.llm.StructuredResult;
import dev.dyad.core.spi.llm.ToolCall;
import dev.dyad.core.spi.llm.ToolDef;
import dev.dyad.core.spi.llm.ToolLoopResult;
import dev.dyad.llm.backend.ChatBackend;
import dev.dyad.llm.backend.ChatCall;
import dev.dyad.llm.backend.ChatResponse;
import dev.dyad.llm.backend.ChatTurn;
import dev.dyad.llm.backend.ToolResult;
import dev.dyad.llm.backend.ToolSpec;
import dev.dyad.llm.backend.ToolUse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The provider-neutral half of the LLM layer: structured extraction, the agentic loop, streaming.
 *
 * <p>Written once against {@link ChatBackend} so that adding a provider means writing a translator,
 * not another loop. The loop in particular is where the subtle bugs live — dropping a tool result,
 * losing the assistant turn that requested it, running forever — and there should only be one of it.
 */
public final class DefaultLlmClient implements LlmClient {

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
        ResponseFormat format =
                req.responseFormat() != null
                        ? req.responseFormat()
                        : ResponseFormat.strict(schemaName(schema), JsonSchemas.forRecord(schema));
        ChatResponse response = backend.chat(call(req, format, List.of()));
        String json = response.text();
        if (json == null || json.isBlank()) {
            throw new LlmException("empty_structured_response", "model returned no content for " + format.name());
        }
        return new StructuredResult<>(
                Json.read(json, schema), json, response.model(), response.usage());
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
            ChatResponse response =
                    backend.chat(
                            new ChatCall(
                                    req.model(),
                                    req.system(),
                                    turns,
                                    specs,
                                    req.responseFormat(),
                                    req.temperature(),
                                    req.maxTokens()));
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
                        output = "Tool failed: " + e.getMessage();
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
        return new ChatCall(
                req.model(), req.system(), toTurns(req.messages()), tools, format, req.temperature(), req.maxTokens());
    }

    private static List<ChatTurn> toTurns(List<LlmMessage> messages) {
        List<ChatTurn> turns = new ArrayList<>(messages.size());
        for (LlmMessage message : messages) {
            turns.add(
                    message.role() == Role.USER
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
        return output.substring(0, maxToolOutputChars)
                + "\n… truncated at "
                + maxToolOutputChars
                + " characters; narrow the query to see the rest.";
    }

    private static String schemaName(Class<?> type) {
        return type.getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }
}
