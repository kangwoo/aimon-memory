package at.aimon.memory.engine.dialectic;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.ConclusionDraft;
import at.aimon.memory.core.model.ConclusionLevel;
import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.core.spi.llm.ToolLoopResult;
import at.aimon.memory.engine.MemoryTestBase;
import at.aimon.memory.llm.DefaultLlmClient;
import at.aimon.memory.llm.LlmMode;
import at.aimon.memory.llm.replay.LlmFixtureStore;
import at.aimon.memory.llm.replay.RecordingChatBackend;
import at.aimon.memory.testkit.stub.StubAnalyzer;
import at.aimon.memory.text.ContentHash;
import at.aimon.memory.text.Normalizer;

/**
 * The Tier 2 gate: a tool loop recorded once and replayed exactly, with the provider unreachable.
 *
 * <p>Recording happens at the provider boundary, so each step of the loop is content-addressed on its
 * own inputs. That is what makes a multi-step agentic run reproducible without the harness knowing
 * anything about loops — and it is also what makes a changed prompt or a changed tool output show up
 * as a miss rather than as a quietly different answer.
 */
class DialecticReplayTest extends MemoryTestBase {

    private final StubAnalyzer analyzer = new StubAnalyzer();
    private PairKey pair;

    @BeforeEach
    void seedMemory() {
        pair = seedPair("alice", "alice");
        seedSession("s1");
        store("alice works at a bank in seoul gangnam");
        store("alice commutes by subway line two");
    }

    private void store(String content) {
        String norm = Normalizer.normalize(content);
        conclusions.upsert(ConclusionDraft.builder().pair(pair).sessionName("s1").content(content).contentNorm(norm)
                .contentAnalyzed(analyzer.analyze(content)).contentHash(ContentHash.of(norm))
                .level(ConclusionLevel.EXPLICIT).embedding(embedder.embed(content, EmbedPurpose.DOCUMENT))
                .actor(Actor.DERIVER).build());
    }

    private DialecticService dialectic(ScriptedBackend backend, LlmFixtureStore fixtures, LlmMode mode) {
        return new DialecticService(new DefaultLlmClient(new RecordingChatBackend(backend, fixtures, mode)), tools);
    }

    private DialecticService.Question question() {
        return new DialecticService.Question(pair, "s1", "where does alice work?", List.of(), ReasoningLevel.LOW, null);
    }

    @Test
    void aRecordedToolLoopReplaysWithoutTouchingTheProvider(@TempDir Path directory) {
        LlmFixtureStore fixtures = new LlmFixtureStore(directory);

        ScriptedBackend live = new ScriptedBackend()
                .requestingTool("call-1", ToolRegistry.RECALL, "{\"query\":\"bank seoul\"}")
                .answering("Alice works at a bank in Gangnam, Seoul.");

        ToolLoopResult recorded = dialectic(live, fixtures, LlmMode.RECORD).answer(question());

        assertThat(recorded.text()).contains("Gangnam");
        assertThat(recorded.iterations()).isEqualTo(2);
        assertThat(recorded.stoppedAtLimit()).isFalse();
        assertThat(recorded.calls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo(ToolRegistry.RECALL);
            assertThat(call.failed()).isFalse();
            // The tool returned the real Tier 1 result, not a placeholder.
            assertThat(call.output()).contains("alice works at a bank in seoul gangnam");
        });

        ScriptedBackend unreachable = new ScriptedBackend().refusing();
        ToolLoopResult replayed = dialectic(unreachable, fixtures, LlmMode.REPLAY).answer(question());

        assertThat(replayed.text()).isEqualTo(recorded.text());
        assertThat(replayed.iterations()).isEqualTo(recorded.iterations());
        assertThat(replayed.calls()).extracting(c -> c.name() + c.argumentsJson())
                .isEqualTo(recorded.calls().stream().map(c -> c.name() + c.argumentsJson()).toList());
    }

    /**
     * Tier 1 as a tool is the point of the arrangement: one good retrieval instead of several rounds
     * of reconstructing the same answer from raw messages.
     */
    @Test
    void theRecallToolReturnsRankedConclusionsWithTheirReinforcementCounts(@TempDir Path directory) {
        ScriptedBackend backend = new ScriptedBackend()
                .requestingTool("call-1", ToolRegistry.RECALL, "{\"query\":\"subway\"}")
                .answering("She takes line two.");

        ToolLoopResult result = dialectic(backend, new LlmFixtureStore(directory), LlmMode.RECORD).answer(question());

        String output = result.calls().get(0).output();
        assertThat(output).contains("alice commutes by subway line two");
        // The model is told how often each fact was confirmed and when, so it can spot a superseded one.
        assertThat(output).contains("confirmed 1 time, last");
    }

    /** A tool failure becomes tool output, and the loop still produces an answer. */
    @Test
    void anUnknownToolNameIsReportedToTheModel(@TempDir Path directory) {
        ScriptedBackend backend = new ScriptedBackend().requestingTool("call-1", "nonexistent_tool", "{}")
                .answering("I could not look that up.");

        ToolLoopResult result = dialectic(backend, new LlmFixtureStore(directory), LlmMode.RECORD).answer(question());

        assertThat(result.calls()).singleElement().satisfies(call -> {
            assertThat(call.failed()).isTrue();
            assertThat(call.output()).contains("No tool named");
        });
        assertThat(result.text()).isEqualTo("I could not look that up.");
    }

    /** The toolset is bound to the reasoning level, so a cheap question cannot reach expensive tools. */
    @Test
    void theReasoningLevelBoundsTheToolsOffered(@TempDir Path directory) {
        ScriptedBackend backend = new ScriptedBackend().answering("done");
        dialectic(backend, new LlmFixtureStore(directory), LlmMode.RECORD)
                .answer(new DialecticService.Question(pair, "s1", "q", List.of(), ReasoningLevel.MINIMAL, null));

        assertThat(backend.calls().get(0).tools()).extracting(t -> t.name()).containsExactly(ToolRegistry.RECALL);
    }

    /** Every tool is bound to the pair at construction; no argument the model can produce leaves it. */
    @Test
    void toolsCannotReachAnotherPairsMemory(@TempDir Path directory) {
        PairKey other = seedPair("bob", "bob");
        ScriptedBackend backend = new ScriptedBackend()
                .requestingTool("call-1", ToolRegistry.RECALL, "{\"query\":\"bank seoul\"}")
                .answering("Nothing is known.");

        ToolLoopResult result = new DialecticService(
                new DefaultLlmClient(new RecordingChatBackend(backend, new LlmFixtureStore(directory), LlmMode.RECORD)),
                tools)
                .answer(new DialecticService.Question(other, "s1", "where does alice work?", List.of(),
                        ReasoningLevel.LOW, null));

        assertThat(result.calls().get(0).output()).isEqualTo("No matches.");
    }
}
