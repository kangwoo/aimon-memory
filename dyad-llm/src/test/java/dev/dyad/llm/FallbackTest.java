package dev.dyad.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dyad.llm.backend.AttemptPlan;
import dev.dyad.llm.backend.ChatCall;
import dev.dyad.llm.backend.ChatTurn;
import dev.dyad.llm.backend.FallbackChatBackend;
import java.util.List;
import org.junit.jupiter.api.Test;

class FallbackTest {

    private static ChatCall call() {
        return new ChatCall("m", "s", List.of(new ChatTurn.UserText("u")), List.of(), null, null, null);
    }

    /**
     * The plan is flat, and this is what that buys.
     *
     * <p>A retry loop nested inside a fallback loop would return to the primary after every fallback,
     * so a provider that is down gets hit once per fallback instead of being abandoned. Here the
     * primary is tried twice, then never again.
     */
    @Test
    void retriesNeverReturnToAnExhaustedProvider() {
        FakeChatBackend primary =
                new FakeChatBackend("primary").failing(new LlmException("llm_retryable", "503"), 99);
        FakeChatBackend secondary = new FakeChatBackend("secondary").answering("from secondary");

        var backend =
                new FallbackChatBackend(AttemptPlan.of(List.of(primary, secondary), 2), 1);

        assertThat(backend.chat(call()).text()).isEqualTo("from secondary");
        assertThat(primary.callCount()).isEqualTo(2);
        assertThat(secondary.callCount()).isEqualTo(1);
    }

    /**
     * A malformed request will be malformed at the next provider too. Walking the whole plan for it
     * costs latency and money to arrive at the same error.
     */
    @Test
    void aRejectedRequestAbortsTheWholePlan() {
        FakeChatBackend primary =
                new FakeChatBackend("primary").failing(new LlmException("llm_rejected", "400 bad schema"), 99);
        FakeChatBackend secondary = new FakeChatBackend("secondary").answering("never reached");

        var backend = new FallbackChatBackend(AttemptPlan.of(List.of(primary, secondary), 2), 1);

        assertThatThrownBy(() -> backend.chat(call()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("400 bad schema");
        assertThat(primary.callCount()).isEqualTo(1);
        assertThat(secondary.callCount()).isZero();
    }

    /** An auth failure is worth a fallback: usually one key is misconfigured, not both. */
    @Test
    void anAuthFailureFallsThroughToTheNextProvider() {
        FakeChatBackend primary =
                new FakeChatBackend("primary").failing(new LlmException("llm_auth", "401"), 99);
        FakeChatBackend secondary = new FakeChatBackend("secondary").answering("ok");

        var backend = new FallbackChatBackend(AttemptPlan.of(List.of(primary, secondary), 1), 1);
        assertThat(backend.chat(call()).text()).isEqualTo("ok");
    }

    @Test
    void exhaustingThePlanReportsTheLastFailure() {
        FakeChatBackend only =
                new FakeChatBackend("only").failing(new LlmException("llm_retryable", "still 503"), 99);
        var backend = new FallbackChatBackend(AttemptPlan.of(List.of(only), 3), 1);

        assertThatThrownBy(() -> backend.chat(call()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("all 3 attempts failed")
                .hasMessageContaining("still 503");
    }

    @Test
    void planOrdersAttemptsProviderByProvider() {
        FakeChatBackend a = new FakeChatBackend("a");
        FakeChatBackend b = new FakeChatBackend("b");
        AttemptPlan plan = AttemptPlan.of(List.of(a, b), 2);

        assertThat(plan.size()).isEqualTo(4);
        assertThat(plan.attempts()).extracting(x -> x.backend().defaultModel())
                .containsExactly("a", "a", "b", "b");
    }

    @Test
    void emptyPlansAreRejected() {
        assertThatThrownBy(() -> AttemptPlan.of(List.of(), 2)).isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> AttemptPlan.of(List.of(new FakeChatBackend("a")), 0))
                .isInstanceOf(LlmException.class);
    }
}
