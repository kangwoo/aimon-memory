package dev.dyad.llm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dyad.core.spi.llm.LlmRequest;
import dev.dyad.core.spi.llm.ToolDef;
import dev.dyad.core.spi.llm.ToolLoopResult;
import dev.dyad.llm.backend.ChatTurn;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolLoopTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

    private static ToolDef tool(String name, ToolDef.ToolHandler handler) {
        return new ToolDef(name, "a tool", SCHEMA, handler);
    }

    @Test
    void feedsToolOutputBackAndStopsWhenTheModelAnswers() {
        FakeChatBackend backend =
                new FakeChatBackend("m").requestingTool("call-1", "lookup", "{}").answering("the answer");
        DefaultLlmClient client = new DefaultLlmClient(backend);

        ToolLoopResult result =
                client.toolLoop(
                        LlmRequest.of("system", "question"),
                        List.of(tool("lookup", arguments -> "tool output")),
                        5);

        assertThat(result.text()).isEqualTo("the answer");
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.stoppedAtLimit()).isFalse();
        assertThat(result.calls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("lookup");
            assertThat(call.output()).isEqualTo("tool output");
            assertThat(call.failed()).isFalse();
        });

        // The second call must carry both the assistant turn that asked and the result that answered.
        List<ChatTurn> secondTurns = backend.calls().get(1).turns();
        assertThat(secondTurns).hasSize(3);
        assertThat(secondTurns.get(1)).isInstanceOf(ChatTurn.AssistantToolUse.class);
        assertThat(secondTurns.get(2)).isInstanceOf(ChatTurn.ToolResults.class);
    }

    /**
     * A failing tool is information, not a crash. Aborting the loop throws away every result the model
     * had already gathered, and it can often answer anyway or retry with different arguments.
     */
    @Test
    void aFailingToolBecomesToolOutput() {
        FakeChatBackend backend =
                new FakeChatBackend("m").requestingTool("call-1", "boom", "{}").answering("recovered");
        DefaultLlmClient client = new DefaultLlmClient(backend);

        ToolLoopResult result =
                client.toolLoop(
                        LlmRequest.of("system", "question"),
                        List.of(tool("boom", arguments -> {
                            throw new IllegalStateException("database is down");
                        })),
                        5);

        assertThat(result.text()).isEqualTo("recovered");
        assertThat(result.calls()).singleElement().satisfies(call -> {
            assertThat(call.failed()).isTrue();
            assertThat(call.output()).contains("database is down");
        });
    }

    @Test
    void anUnknownToolIsReportedRatherThanThrown() {
        FakeChatBackend backend =
                new FakeChatBackend("m").requestingTool("call-1", "ghost", "{}").answering("ok");
        ToolLoopResult result =
                new DefaultLlmClient(backend)
                        .toolLoop(LlmRequest.of("system", "q"), List.of(tool("real", a -> "x")), 5);

        assertThat(result.calls()).singleElement().satisfies(call -> {
            assertThat(call.failed()).isTrue();
            assertThat(call.output()).contains("No tool named 'ghost'");
        });
    }

    @Test
    void stopsAtTheIterationLimitAndSaysSo() {
        FakeChatBackend backend = new FakeChatBackend("m");
        for (int i = 0; i < 10; i++) {
            backend.requestingTool("call-" + i, "loop", "{}");
        }
        AtomicInteger invocations = new AtomicInteger();
        ToolLoopResult result =
                new DefaultLlmClient(backend)
                        .toolLoop(
                                LlmRequest.of("system", "q"),
                                List.of(tool("loop", a -> "n=" + invocations.incrementAndGet())),
                                3);

        assertThat(result.iterations()).isEqualTo(3);
        // The caller has to be able to tell a finished answer from a truncated one.
        assertThat(result.stoppedAtLimit()).isTrue();
        assertThat(invocations).hasValue(3);
    }

    @Test
    void oversizedToolOutputIsTruncated() {
        FakeChatBackend backend =
                new FakeChatBackend("m").requestingTool("call-1", "big", "{}").answering("done");
        ToolLoopResult result =
                new DefaultLlmClient(backend, 100)
                        .toolLoop(
                                LlmRequest.of("system", "q"),
                                List.of(tool("big", a -> "x".repeat(10_000))),
                                5);

        assertThat(result.calls().get(0).output()).hasSizeLessThan(300).contains("truncated at 100");
    }
}
