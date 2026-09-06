package at.aimon.memory.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.MemoryException;
import at.aimon.memory.core.spi.llm.LlmRequest;
import at.aimon.memory.core.spi.llm.ToolDef;
import at.aimon.memory.core.spi.llm.ToolLoopResult;
import at.aimon.memory.llm.backend.ChatTurn;

class ToolLoopTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

    private static ToolDef tool(String name, ToolDef.ToolHandler handler) {
        return new ToolDef(name, "a tool", SCHEMA, handler);
    }

    @Test
    void feedsToolOutputBackAndStopsWhenTheModelAnswers() {
        FakeChatBackend backend = new FakeChatBackend("m").requestingTool("call-1", "lookup", "{}")
                .answering("the answer");
        DefaultLlmClient client = new DefaultLlmClient(backend);

        ToolLoopResult result = client.toolLoop(LlmRequest.of("system", "question"),
                List.of(tool("lookup", arguments -> "tool output")), 5);

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
     *
     * <p>What the model is told about the failure is summarised, not quoted. This is the longest route
     * a message can take towards a caller: the model reads the tool result and its answer is the 200
     * body of {@code POST /chat}, so a driver's words handed in here can come back out in prose. The
     * tools are recall and search, both of which reach the store, and a {@code DataAccessException}
     * there carries the statement, the relation and — on a not-null or check violation — the failing
     * row. The exception's own message would once have been pasted in whole.
     */
    @Test
    void aFailingToolBecomesToolOutputWithoutQuotingTheFailure() {
        FakeChatBackend backend = new FakeChatBackend("m").requestingTool("call-1", "boom", "{}")
                .answering("recovered");
        DefaultLlmClient client = new DefaultLlmClient(backend);

        ToolLoopResult result = client.toolLoop(LlmRequest.of("system", "question"), List.of(tool("boom", arguments -> {
            throw new IllegalStateException("ERROR: null value in column \"secret\" of relation \"conclusions\"");
        })), 5);

        assertThat(result.text()).isEqualTo("recovered");
        assertThat(result.calls()).singleElement().satisfies(call -> {
            assertThat(call.failed()).isTrue();
            // Still says a tool failed — that is what the model needs to change course.
            assertThat(call.output()).isEqualTo("Tool failed: an internal failure; see the server log");
        });
        // And the turn the model actually reads carries no more than the record does.
        assertThat(backend.calls().get(1).turns().toString()).doesNotContain("relation").doesNotContain("secret");
    }

    /**
     * A failure this build worded is still handed over whole.
     *
     * <p>Which is the point of the split rather than a concession to it: "filter field 'colour' is not
     * in the allow list" is what lets the model call the tool again with something that works, and it
     * repeats a value the caller put in the request. Summarising everything would have cost the
     * recovery this loop exists to allow.
     */
    @Test
    void aFailureThisBuildWordedStillReachesTheModel() {
        FakeChatBackend backend = new FakeChatBackend("m").requestingTool("call-1", "search", "{}")
                .answering("recovered");

        ToolLoopResult result = new DefaultLlmClient(backend).toolLoop(LlmRequest.of("system", "question"),
                List.of(tool("search", arguments -> {
                    throw new MemoryException("bad_filter", "filter field 'colour' is not in the allow list");
                })), 5);

        assertThat(result.calls()).singleElement().satisfies(call -> assertThat(call.output())
                .isEqualTo("Tool failed: filter field 'colour' is not in the allow list"));
    }

    @Test
    void anUnknownToolIsReportedRatherThanThrown() {
        FakeChatBackend backend = new FakeChatBackend("m").requestingTool("call-1", "ghost", "{}").answering("ok");
        ToolLoopResult result = new DefaultLlmClient(backend).toolLoop(LlmRequest.of("system", "q"),
                List.of(tool("real", a -> "x")), 5);

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
        ToolLoopResult result = new DefaultLlmClient(backend).toolLoop(LlmRequest.of("system", "q"),
                List.of(tool("loop", a -> "n=" + invocations.incrementAndGet())), 3);

        assertThat(result.iterations()).isEqualTo(3);
        // The caller has to be able to tell a finished answer from a truncated one.
        assertThat(result.stoppedAtLimit()).isTrue();
        assertThat(invocations).hasValue(3);
    }

    @Test
    void oversizedToolOutputIsTruncated() {
        FakeChatBackend backend = new FakeChatBackend("m").requestingTool("call-1", "big", "{}").answering("done");
        ToolLoopResult result = new DefaultLlmClient(backend, 100).toolLoop(LlmRequest.of("system", "q"),
                List.of(tool("big", a -> "x".repeat(10_000))), 5);

        assertThat(result.calls().get(0).output()).hasSizeLessThan(300).contains("truncated at 100");
    }
}
