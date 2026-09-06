package at.aimon.memory.engine.dialectic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.MemoryException;
import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.spi.llm.ToolDef;

/**
 * A model that writes malformed tool arguments has to be told so.
 *
 * <p>{@code DefaultLlmClient} catches a failing tool, turns it into a tool result and keeps the loop
 * going, which is what lets a model correct its own call. It now summarises anything that is not a
 * {@code MemoryException} to one sentence, because the rest of that catch holds messages a driver or a
 * library wrote for the log and two sinks copy the result into a response. This message is the other
 * kind, so it has to be the other type — and it was an {@code IllegalArgumentException}, which would
 * have reached the model as "an internal failure; see the server log" and taken the retry with it.
 *
 * <p>No collaborators and no database: the guard runs before the tool touches either, which is half of
 * what is being asserted here. Nothing else in this class would work with these nulls.
 */
class ToolArgumentsTest {

    @Test
    void malformedArgumentsAreReportedToTheModelRatherThanSummarised() {
        ToolDef recall = new ToolRegistry(null, null, null, null)
                .toolsFor(PairKey.self("ws", "alice"), "s1", List.of("recall")).get(0);

        Throwable thrown = catchThrowable(() -> recall.handler().invoke("{\"query\": "));

        assertThat(thrown).isInstanceOf(MemoryException.class);
        assertThat(MemoryException.publicMessageOf(thrown)).isEqualTo(thrown.getMessage())
                .startsWith("arguments are not valid JSON:");
    }
}
