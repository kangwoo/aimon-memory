package at.aimon.memory.store.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.memory.core.model.Message;
import at.aimon.memory.store.StoreTestBase;

/**
 * What an observer may search: the messages spoken while they were in the room.
 *
 * <p>Scoped to the single {@code session_peers} row, this was right until someone left and came back.
 * Re-entry moves that row's {@code joined_at} forward and there is nowhere else the earlier window is
 * kept, so a peer removed from a session and then speaking in it again lost search access to
 * everything before the rejoin — their own transcript included — and the dialectic reported it as "no
 * messages found" rather than as a boundary. Widening to "has ever been a member" would have handed
 * back the gap they genuinely did not hear, so the windows are kept instead.
 */
class MessageAudibilityTest extends StoreTestBase {

    @BeforeEach
    void seedPeers() {
        seedSession("s1");
        peers.getOrCreate(WORKSPACE, "alice", Map.of(), Map.of());
        peers.getOrCreate(WORKSPACE, "carol", Map.of(), Map.of());
    }

    private void say(String speaker, String content) {
        messages.insertBatch(WORKSPACE, "s1", sessions.nextSequence(WORKSPACE, "s1", 1),
                List.of(new MessageRepository.NewMessage(speaker, content, 4, Map.of())));
    }

    private List<String> aliceSearches(String needle) {
        return messages.grep(WORKSPACE, "alice", "s1", needle, 50).stream().map(Message::content).toList();
    }

    @Test
    void aRejoinedPeerKeepsWhatTheyHeardBefore() {
        sessionPeers.join(WORKSPACE, "s1", "alice");
        say("alice", "era one: alice works at a bank");

        sessionPeers.leave(WORKSPACE, "s1", "alice");
        say("carol", "era two: said while alice was away");

        sessionPeers.join(WORKSPACE, "s1", "alice");
        say("alice", "era three: alice is back");

        assertThat(aliceSearches("era")).containsExactlyInAnyOrder("era one: alice works at a bank",
                "era three: alice is back");
    }

    /** The gap is still a boundary. Coming back is not a licence to read what was said meanwhile. */
    @Test
    void aRejoinDoesNotHandBackTheGap() {
        sessionPeers.join(WORKSPACE, "s1", "alice");
        sessionPeers.leave(WORKSPACE, "s1", "alice");
        say("carol", "era two: said while alice was away");
        sessionPeers.join(WORKSPACE, "s1", "alice");

        assertThat(aliceSearches("era two")).isEmpty();
    }

    /** Someone who was never in the room reads nothing, which is the case that already worked. */
    @Test
    void aPeerWhoNeverJoinedSeesNothing() {
        sessionPeers.join(WORKSPACE, "s1", "carol");
        say("carol", "era one: carol says something");

        assertThat(aliceSearches("era one")).isEmpty();
    }

    /** Removing a peer from the roster closes their window too, not only their membership row. */
    @Test
    void replacingTheRosterClosesTheDepartedWindows() {
        sessionPeers.join(WORKSPACE, "s1", "alice");
        sessionPeers.join(WORKSPACE, "s1", "carol");
        say("carol", "era one: before the roster changed");

        sessionPeers.replace(WORKSPACE, "s1", List.of(SessionPeerRepository.Membership.of("carol")));
        say("carol", "era two: after alice was dropped");

        assertThat(aliceSearches("era")).containsExactly("era one: before the roster changed");
    }

    /** The other two tools take the same scope, so they are asserted rather than assumed. */
    @Test
    void theSameWindowsGovernKeywordAndDateSearch() {
        sessionPeers.join(WORKSPACE, "s1", "alice");
        say("alice", "era one: bank");
        sessionPeers.leave(WORKSPACE, "s1", "alice");
        say("carol", "era two: bank");
        sessionPeers.join(WORKSPACE, "s1", "alice");
        say("alice", "era three: bank");

        assertThat(messages.searchText(WORKSPACE, "alice", "s1", "bank", 50)).extracting(Message::content)
                .containsExactlyInAnyOrder("era one: bank", "era three: bank");

        assertThat(messages.byDateRange(WORKSPACE, "alice", "s1", java.time.Instant.now().minusSeconds(600),
                java.time.Instant.now().plusSeconds(600), 50)).extracting(Message::content)
                .containsExactly("era one: bank", "era three: bank");
    }
}
