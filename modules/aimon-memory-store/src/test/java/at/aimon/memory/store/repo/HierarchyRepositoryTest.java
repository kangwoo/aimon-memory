package at.aimon.memory.store.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import at.aimon.memory.store.StoreTestBase;

class HierarchyRepositoryTest extends StoreTestBase {

    @Test
    void getOrCreateIsIdempotent() {
        workspaces.getOrCreate(WORKSPACE, Map.of("a", 1), Map.of());
        workspaces.getOrCreate(WORKSPACE, Map.of("a", 2), Map.of());

        assertThat(workspaces.find(WORKSPACE)).isPresent();
        // The second call must not overwrite: two clients lazily creating the same workspace would
        // otherwise clobber each other's metadata.
        assertThat(workspaces.find(WORKSPACE).orElseThrow().metadata()).containsEntry("a", 1);
    }

    @Test
    void sequenceAllocationIsMonotonicUnderConcurrency() throws Exception {
        seedSession("s1");
        int writers = 16;
        int perWriter = 5;

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Long>> tasks = IntStream.range(0, writers)
                    .<Callable<Long>>mapToObj(i -> () -> sessions.nextSequence(WORKSPACE, "s1", perWriter)).toList();
            List<Long> starts = pool.invokeAll(tasks).stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).sorted().toList();

            // Every writer gets a contiguous block, and no two blocks overlap.
            assertThat(starts).doesNotHaveDuplicates();
            for (int i = 0; i < starts.size(); i++) {
                assertThat(starts.get(i)).isEqualTo(1L + (long) i * perWriter);
            }
        }
    }

    @Test
    void messageBatchGetsContiguousSequence() {
        seedSession("s1");
        peers.getOrCreate(WORKSPACE, "alice", Map.of(), Map.of());

        long start = sessions.nextSequence(WORKSPACE, "s1", 3);
        var saved = messages.insertBatch(WORKSPACE, "s1", start,
                List.of(new MessageRepository.NewMessage("alice", "one", 1, Map.of()),
                        new MessageRepository.NewMessage("alice", "two", 1, Map.of()),
                        new MessageRepository.NewMessage("alice", "three", 1, Map.of())));

        assertThat(saved).extracting(m -> m.seqInSession()).containsExactly(1L, 2L, 3L);
        assertThat(messages.tail(WORKSPACE, "s1", 2)).extracting(m -> m.content()).containsExactly("two", "three");
    }

    /** The nullable start bound is a separate SQL path; without an explicit cast Postgres rejects it. */
    @Test
    void messagesCanBeReadFromTheStartOrFromAnOffset() {
        seedSession("s1");
        peers.getOrCreate(WORKSPACE, "alice", Map.of(), Map.of());
        long start = sessions.nextSequence(WORKSPACE, "s1", 3);
        messages.insertBatch(WORKSPACE, "s1", start,
                List.of(new MessageRepository.NewMessage("alice", "one", 1, Map.of()),
                        new MessageRepository.NewMessage("alice", "two", 1, Map.of()),
                        new MessageRepository.NewMessage("alice", "three", 1, Map.of())));

        assertThat(messages.inSession(WORKSPACE, "s1", null, 10)).extracting(m -> m.content()).containsExactly("one",
                "two", "three");
        assertThat(messages.inSession(WORKSPACE, "s1", 2L, 10)).extracting(m -> m.content()).containsExactly("two",
                "three");
    }

    /** Wholesale roster replacement: everyone not named leaves, everyone named is in. */
    @Test
    void replacingTheRosterClosesTheWindowsOfEveryoneOmitted() {
        seedSession("s1");
        for (String name : List.of("alice", "bob", "carol")) {
            peers.getOrCreate(WORKSPACE, name, Map.of(), Map.of());
            sessionPeers.join(WORKSPACE, "s1", name, true, true);
        }

        peers.getOrCreate(WORKSPACE, "dave", Map.of(), Map.of());
        sessionPeers.replace(WORKSPACE, "s1", List.of(new SessionPeerRepository.Membership("alice", true, false),
                SessionPeerRepository.Membership.of("dave")));

        Instant now = Instant.now().plusSeconds(1);
        assertThat(sessionPeers.membersAt(WORKSPACE, "s1", now)).extracting(p -> p.peerName()).containsExactly("alice",
                "dave");
        // The rows survive: past messages still need to know who was there when they were sent.
        assertThat(sessionPeers.members(WORKSPACE, "s1")).hasSize(4);
        // Observe settings arrive with the membership rather than in a second write, so there is no
        // window in which alice is observing others against the caller's wishes.
        assertThat(sessionPeers.find(WORKSPACE, "s1", "alice").orElseThrow().observeOthers()).isFalse();
        assertThat(sessionPeers.find(WORKSPACE, "s1", "bob").orElseThrow().leftAt()).isNotNull();
        assertThat(sessionPeers.find(WORKSPACE, "s1", "alice").orElseThrow().leftAt()).isNull();
    }

    /** An empty roster removes everyone rather than silently doing nothing. */
    @Test
    void replacingWithAnEmptyRosterEmptiesTheSession() {
        seedSession("s1");
        peers.getOrCreate(WORKSPACE, "alice", Map.of(), Map.of());
        sessionPeers.join(WORKSPACE, "s1", "alice", true, true);

        sessionPeers.replace(WORKSPACE, "s1", List.of());

        assertThat(sessionPeers.membersAt(WORKSPACE, "s1", Instant.now().plusSeconds(1))).isEmpty();
    }

    @Test
    void membershipWindowAnswersWhoWasPresent() {
        seedSession("s1");
        peers.getOrCreate(WORKSPACE, "alice", Map.of(), Map.of());
        peers.getOrCreate(WORKSPACE, "bob", Map.of(), Map.of());

        sessionPeers.join(WORKSPACE, "s1", "alice", true, true);
        sessionPeers.join(WORKSPACE, "s1", "bob", true, true);
        sessionPeers.leave(WORKSPACE, "s1", "bob");

        Instant now = Instant.now().plusSeconds(1);
        assertThat(sessionPeers.membersAt(WORKSPACE, "s1", now)).extracting(p -> p.peerName()).containsExactly("alice");
        // The row survives the leave, because past messages still need to know bob was there.
        assertThat(sessionPeers.members(WORKSPACE, "s1")).hasSize(2);
    }
}
