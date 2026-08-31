package dev.dyad.memory.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dyad.core.key.PairKey;
import dev.dyad.core.model.Message;
import dev.dyad.core.model.SessionPeer;
import dev.dyad.store.WorkspaceSettings;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObserverResolverTest {

    private static final Instant SENT = Instant.parse("2026-08-31T12:00:00Z");

    private static Message message(String speaker) {
        return new Message(1, "ws", "s1", speaker, "hello", 1, 2, Map.of(), SENT);
    }

    private static SessionPeer member(String name, Boolean observeMe, Boolean observeOthers) {
        return new SessionPeer("ws", "s1", name, observeMe, observeOthers, SENT.minusSeconds(3600), null);
    }

    @Test
    void bothSwitchesOnProducesSelfAndObserverPairs() {
        List<PairKey> pairs =
                ObserverResolver.observersOf(
                        message("alice"),
                        List.of(member("alice", true, true), member("bob", true, true)),
                        WorkspaceSettings.DEFAULT);

        assertThat(pairs)
                .containsExactly(PairKey.self("ws", "alice"), new PairKey("ws", "bob", "alice"));
    }

    @Test
    void observeMeOffDropsTheSelfPair() {
        List<PairKey> pairs =
                ObserverResolver.observersOf(
                        message("alice"),
                        List.of(member("alice", false, true), member("bob", true, true)),
                        WorkspaceSettings.DEFAULT);

        assertThat(pairs).containsExactly(new PairKey("ws", "bob", "alice"));
    }

    @Test
    void observeOthersOffDropsThatListenerOnly() {
        List<PairKey> pairs =
                ObserverResolver.observersOf(
                        message("alice"),
                        List.of(member("alice", true, true), member("bob", true, false), member("carol", true, true)),
                        WorkspaceSettings.DEFAULT);

        assertThat(pairs)
                .containsExactly(PairKey.self("ws", "alice"), new PairKey("ws", "carol", "alice"));
    }

    /** Narrowest scope wins: a session-level null defers to the workspace default. */
    @Test
    void nullOverridesFallBackToTheWorkspaceDefault() {
        WorkspaceSettings quiet =
                new WorkspaceSettings(
                        "und",
                        dev.dyad.core.config.RecallSettings.DEFAULT,
                        dev.dyad.core.config.DedupSettings.DEFAULT,
                        dev.dyad.core.config.BatchSettings.DEFAULT,
                        false,
                        false);

        assertThat(
                        ObserverResolver.observersOf(
                                message("alice"),
                                List.of(member("alice", null, null), member("bob", null, null)),
                                quiet))
                .isEmpty();

        // An explicit session-level true overrides a workspace default of false.
        assertThat(
                        ObserverResolver.observersOf(
                                message("alice"),
                                List.of(member("alice", true, null), member("bob", null, null)),
                                quiet))
                .containsExactly(PairKey.self("ws", "alice"));
    }

    /**
     * Someone who joined after a message was sent did not hear it, and their memory must not contain
     * it. Fan-out asks who was present then, not who is present now.
     */
    @Test
    void peersOutsideTheMembershipWindowAreExcluded() {
        SessionPeer late =
                new SessionPeer("ws", "s1", "late", true, true, SENT.plusSeconds(60), null);
        SessionPeer departed =
                new SessionPeer("ws", "s1", "gone", true, true, SENT.minusSeconds(3600), SENT.minusSeconds(60));

        List<PairKey> pairs =
                ObserverResolver.observersOf(
                        message("alice"),
                        List.of(member("alice", true, true), late, departed, member("bob", true, true)),
                        WorkspaceSettings.DEFAULT);

        assertThat(pairs)
                .containsExactly(PairKey.self("ws", "alice"), new PairKey("ws", "bob", "alice"));
    }

    @Test
    void aSpeakerAbsentFromTheRosterStillObservesThemselves() {
        assertThat(
                        ObserverResolver.observersOf(
                                message("stranger"), List.of(member("bob", true, false)), WorkspaceSettings.DEFAULT))
                .containsExactly(PairKey.self("ws", "stranger"));
    }
}
