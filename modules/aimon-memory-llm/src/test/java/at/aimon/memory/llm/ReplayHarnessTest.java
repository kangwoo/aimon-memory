package at.aimon.memory.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.memory.core.MemoryException;
import at.aimon.memory.core.spi.llm.LlmRequest;
import at.aimon.memory.core.spi.llm.ResponseFormat;
import at.aimon.memory.core.spi.llm.ToolDef;
import at.aimon.memory.llm.backend.ChatBackend;
import at.aimon.memory.llm.backend.ChatCall;
import at.aimon.memory.llm.backend.ChatTurn;
import at.aimon.memory.llm.replay.FixtureKey;
import at.aimon.memory.llm.replay.FixtureMissException;
import at.aimon.memory.llm.replay.LlmFixtureStore;
import at.aimon.memory.llm.replay.RecordingChatBackend;

class ReplayHarnessTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},"
            + "\"required\":[\"answer\"],\"additionalProperties\":false}";

    public record Answer(String answer) {
    }

    private static ChatCall call(String system, String user) {
        return new ChatCall("gpt-test", system, List.of(new ChatTurn.UserText(user)), List.of(),
                ResponseFormat.strict("answer", SCHEMA), null, null);
    }

    @Test
    void recordThenReplayReproducesTheResponse(@TempDir Path directory) {
        LlmFixtureStore store = new LlmFixtureStore(directory);
        FakeChatBackend live = new FakeChatBackend("gpt-test").answering("{\"answer\":\"forty two\"}");

        var recording = new RecordingChatBackend(live, store, LlmMode.RECORD);
        var recorded = new DefaultLlmClient(recording).structured(
                new LlmRequest("gpt-test", "system", List.of(at.aimon.memory.core.spi.llm.LlmMessage.user("q")), null,
                        null, ResponseFormat.strict("answer", SCHEMA)),
                Answer.class);
        assertThat(recorded.value().answer()).isEqualTo("forty two");

        // Replay must not reach the provider at all.
        FakeChatBackend forbidden = new FakeChatBackend("gpt-test");
        var replaying = new RecordingChatBackend(forbidden, store, LlmMode.REPLAY);
        var replayed = new DefaultLlmClient(replaying).structured(
                new LlmRequest("gpt-test", "system", List.of(at.aimon.memory.core.spi.llm.LlmMessage.user("q")), null,
                        null, ResponseFormat.strict("answer", SCHEMA)),
                Answer.class);

        assertThat(replayed.value().answer()).isEqualTo("forty two");
        assertThat(forbidden.callCount()).isZero();
    }

    /**
     * The alarm the harness exists for. A prompt edit changes the key, the fixture misses, and the
     * test fails — instead of the change sailing through unnoticed.
     */
    @Test
    void changingThePromptCausesAMiss(@TempDir Path directory) {
        LlmFixtureStore store = new LlmFixtureStore(directory);
        new RecordingChatBackend(new FakeChatBackend("gpt-test").answering("{\"answer\":\"a\"}"), store, LlmMode.RECORD)
                .chat(call("original prompt", "q"));

        ChatBackend replaying = new RecordingChatBackend(new FakeChatBackend("gpt-test"), store, LlmMode.REPLAY);

        assertThat(replaying.chat(call("original prompt", "q"))).isNotNull();
        assertThatThrownBy(() -> replaying.chat(call("edited prompt", "q"))).isInstanceOf(FixtureMissException.class)
                .hasMessageContaining("AIMON_MEMORY_LLM_MODE=record");
    }

    /**
     * The two audiences, kept apart on the exception rather than at one of its readers.
     *
     * <p>{@code getMessage} is written for a developer staring at a failed {@code ./gradlew test}, and
     * the test above depends on it: the whole point of the harness is that a prompt edit shows you the
     * new prompt. {@code publicMessage} is what anything copying a message towards a caller uses, and
     * it has to be free of all of it — the assembled prompt, the absolute fixtures path, and the
     * re-record recipe, which names the environment variable that turns replay off.
     *
     * <p>Pinned here, not only at the HTTP boundary, because {@code ApiExceptionHandler} is not the
     * only reader: a failed dream stores its message in a column {@code Dtos.DreamResponse} returns in
     * a 200.
     */
    @Test
    void theDiagnosticIsForTheDeveloperAndTheWireMessageForNobodyElse(@TempDir Path directory) {
        ChatBackend replaying = new RecordingChatBackend(new FakeChatBackend("gpt-test"),
                new LlmFixtureStore(directory), LlmMode.REPLAY);

        FixtureMissException thrown = (FixtureMissException) catchThrowable(
                () -> replaying.chat(call("a system prompt nobody outside should read", "q")));

        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage()).contains("a system prompt nobody outside should read")
                .contains(directory.toString()).contains("AIMON_MEMORY_LLM_MODE=record");
        assertThat(thrown.publicMessage()).doesNotContain("a system prompt nobody outside should read")
                .doesNotContain(directory.toString()).doesNotContain("AIMON_MEMORY_LLM_MODE")
                .isEqualTo("no recorded LLM fixture matches this request; see the server log");
        assertThat(MemoryException.publicMessageOf(thrown)).isEqualTo(thrown.publicMessage());
    }

    /** Every other failure is written for the caller, so the two are the same string. */
    @Test
    void anOrdinaryMemoryExceptionKeepsItsWholeMessage() {
        MemoryException ordinary = new MemoryException("store_failed", "entity 'alice' was not found");

        assertThat(ordinary.publicMessage()).isEqualTo("entity 'alice' was not found");
        assertThat(MemoryException.publicMessageOf(ordinary)).isEqualTo("entity 'alice' was not found");
    }

    /**
     * Being a {@code MemoryException} is what says a message was written for the caller. Nothing else
     * carries that claim, so nothing else is quoted.
     *
     * <p>This used to return the message. The population it let through is every exception the JDK,
     * Spring and the driver raise, and a Postgres error alone names the statement, the constraint and
     * the relation, and on a not-null or check violation appends the failing row. Two sinks copy the
     * result of this call into a column an endpoint returns in a 200, so "the caller sees only what
     * was written for the caller" held for the {@code MemoryException} half of the catch and for no
     * other.
     */
    @Test
    void aFailureNobodyHereWordedIsSummarisedRatherThanQuoted() {
        String driver = "PreparedStatementCallback; SQL [INSERT INTO conclusions …]; ERROR: null value in"
                + " column \"tenant_id\" violates not-null constraint  Detail: Failing row contains (…)";

        assertThat(MemoryException.publicMessageOf(new IllegalStateException(driver)))
                .isEqualTo("an internal failure; see the server log");
        // Including the one whose message is null: a caller must not be handed "null" either.
        assertThat(MemoryException.publicMessageOf(new NullPointerException()))
                .isEqualTo("an internal failure; see the server log");
    }

    @Test
    void keyDependsOnModelSystemMessagesToolsAndSchema() {
        ChatCall base = call("system", "user");
        String key = FixtureKey.of(base);

        assertThat(FixtureKey.of(base.withModel("other-model"))).isNotEqualTo(key);
        assertThat(FixtureKey.of(call("other-system", "user"))).isNotEqualTo(key);
        assertThat(FixtureKey.of(call("system", "other-user"))).isNotEqualTo(key);

        ChatCall withTool = new ChatCall(base.model(), base.system(), base.turns(),
                List.of(new at.aimon.memory.llm.backend.ToolSpec("t", "d", "{}")), base.responseFormat(), null, null);
        assertThat(FixtureKey.of(withTool)).isNotEqualTo(key);

        ChatCall withoutSchema = new ChatCall(base.model(), base.system(), base.turns(), List.of(), null, null, null);
        assertThat(FixtureKey.of(withoutSchema)).isNotEqualTo(key);
    }

    /**
     * Streaming is in the key, because the two recordings have different shapes and nothing else tells
     * them apart. Sharing a key meant a replayed stream found the chat fixture, read a null chunk list
     * and returned an empty stream — an SSE response that completed successfully having emitted
     * nothing, with no miss raised to say the fixture was the wrong kind.
     */
    /**
     * Separating the two kinds must not rehash the blocking corpus.
     *
     * <p>Emitting {@code "stream": false} would have moved every key ever recorded, turning each
     * existing fixture into a {@link FixtureMissException} — a 503 per call, and in CI, where the mode
     * is {@code replay}, a suite-wide failure that only re-recording against a live provider could
     * clear. The new kind is the one with nothing recorded yet, so it is the one that moves.
     */
    @Test
    void addingTheStreamFlagLeavesBlockingKeysWhereTheyWere() {
        ChatCall base = call("system", "user");
        assertThat(FixtureKey.canonical(base, false))
                .as("a blocking call's canonical form must not mention streaming at all").doesNotContain("stream");
        assertThat(FixtureKey.canonical(base, true)).contains("\"stream\":true");
    }

    @Test
    void streamingAndBlockingCallsDoNotShareAKey(@TempDir Path directory) {
        ChatCall base = call("system", "user");
        assertThat(FixtureKey.of(base, true)).isNotEqualTo(FixtureKey.of(base, false));

        LlmFixtureStore store = new LlmFixtureStore(directory);
        new RecordingChatBackend(new FakeChatBackend("gpt-test").answering("{\"answer\":\"blocking\"}"), store,
                LlmMode.RECORD).chat(base);

        // The chat recording must not answer a stream. Before, this returned an empty stream instead.
        ChatBackend replaying = new RecordingChatBackend(new FakeChatBackend("gpt-test"), store, LlmMode.REPLAY);
        assertThatThrownBy(() -> replaying.stream(base).toList()).isInstanceOf(FixtureMissException.class);

        // And recording the stream must not erase the chat response already on disk.
        new RecordingChatBackend(new FakeChatBackend("gpt-test").streaming("one", "two"), store, LlmMode.RECORD)
                .stream(base).toList();

        assertThat(replaying.chat(base).text()).isEqualTo("{\"answer\":\"blocking\"}");
        assertThat(
                new RecordingChatBackend(new FakeChatBackend("gpt-test"), store, LlmMode.REPLAY).stream(base).toList())
                .containsExactly("one", "two");
    }

    /**
     * Temperature and max tokens are deliberately outside the key: a fixture is a frozen answer, and
     * keying on a budget would invalidate the whole corpus every time one moved.
     */
    @Test
    void keyIgnoresTemperatureAndMaxTokens() {
        ChatCall base = call("system", "user");
        ChatCall tuned = new ChatCall(base.model(), base.system(), base.turns(), base.tools(), base.responseFormat(),
                0.9, 4096);
        assertThat(FixtureKey.of(tuned)).isEqualTo(FixtureKey.of(base));
    }

    /**
     * Provider call ids are random per response. Including them would make the second turn of any
     * recorded tool loop unreplayable.
     */
    @Test
    void keyIgnoresProviderAssignedToolCallIds() {
        ChatCall first = new ChatCall("m", "s",
                List.of(new ChatTurn.AssistantToolUse(null,
                        List.of(new at.aimon.memory.llm.backend.ToolUse("call_abc", "t", "{}")))),
                List.of(), null, null, null);
        ChatCall second = new ChatCall("m", "s",
                List.of(new ChatTurn.AssistantToolUse(null,
                        List.of(new at.aimon.memory.llm.backend.ToolUse("call_xyz", "t", "{}")))),
                List.of(), null, null, null);

        assertThat(FixtureKey.of(first)).isEqualTo(FixtureKey.of(second));
    }

    /** A null model must resolve to the backend's default before hashing, or one key serves two models. */
    @Test
    void nullModelIsResolvedBeforeHashing(@TempDir Path directory) {
        LlmFixtureStore store = new LlmFixtureStore(directory);
        var recording = new RecordingChatBackend(new FakeChatBackend("resolved-model").answering("{}"), store,
                LlmMode.RECORD);

        ChatCall unresolved = new ChatCall(null, "s", List.of(new ChatTurn.UserText("u")), List.of(), null, null, null);
        recording.chat(unresolved);

        ChatCall resolved = unresolved.withModel("resolved-model");
        assertThat(store.find(FixtureKey.of(resolved))).isPresent();
    }

    @Test
    void toolLoopReplaysStepByStep(@TempDir Path directory) {
        LlmFixtureStore store = new LlmFixtureStore(directory);
        FakeChatBackend live = new FakeChatBackend("gpt-test").requestingTool("c1", "lookup", "{}").answering("final");

        ToolDef lookup = new ToolDef("lookup", "d", "{\"type\":\"object\"}", a -> "deterministic output");

        var recorded = new DefaultLlmClient(new RecordingChatBackend(live, store, LlmMode.RECORD))
                .toolLoop(LlmRequest.of("system", "q"), List.of(lookup), 5);
        assertThat(recorded.iterations()).isEqualTo(2);

        // Both provider round trips replay from their own fixtures, with no notion of a loop in the
        // harness at all — that is what recording at the provider boundary buys.
        FakeChatBackend forbidden = new FakeChatBackend("gpt-test");
        var replayed = new DefaultLlmClient(new RecordingChatBackend(forbidden, store, LlmMode.REPLAY))
                .toolLoop(LlmRequest.of("system", "q"), List.of(lookup), 5);

        assertThat(replayed.text()).isEqualTo("final");
        assertThat(replayed.iterations()).isEqualTo(2);
        assertThat(forbidden.callCount()).isZero();
    }
}
