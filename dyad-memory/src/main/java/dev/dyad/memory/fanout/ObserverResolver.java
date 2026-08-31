package dev.dyad.memory.fanout;

import dev.dyad.core.key.PairKey;
import dev.dyad.core.model.Message;
import dev.dyad.core.model.SessionPeer;
import dev.dyad.store.WorkspaceSettings;
import java.util.ArrayList;
import java.util.List;

/**
 * Works out which pairs a message should be filed under.
 *
 * <p>Two switches, resolved workspace → session → message with the narrowest scope winning:
 *
 * <ul>
 *   <li>{@code observe_me} — the speaker keeps a memory of themselves, pair {@code (p, p)}
 *   <li>{@code observe_others} — a listener keeps a memory of the speaker, pair {@code (listener, speaker)}
 * </ul>
 *
 * <p>Fan-out is over storage, not over model calls: one extraction runs and its output is written to
 * every resulting pair. Getting that backwards makes a five-person session cost five times as much
 * for the same answer.
 *
 * <p>Only peers whose membership window covers the message are considered. Someone who joined an hour
 * later did not hear it, and their memory should not contain it.
 */
public final class ObserverResolver {

    private ObserverResolver() {}

    public static List<PairKey> observersOf(
            Message message, List<SessionPeer> members, WorkspaceSettings settings) {
        String workspace = message.workspaceName();
        String speaker = message.peerName();
        List<PairKey> pairs = new ArrayList<>();

        SessionPeer speakerMembership = find(members, speaker);
        if (resolve(speakerMembership == null ? null : speakerMembership.observeMe(), settings.observeMe())) {
            pairs.add(PairKey.self(workspace, speaker));
        }

        for (SessionPeer member : members) {
            if (member.peerName().equals(speaker)) {
                continue;
            }
            if (!member.wasPresentAt(message.createdAt())) {
                continue;
            }
            if (resolve(member.observeOthers(), settings.observeOthers())) {
                pairs.add(new PairKey(workspace, member.peerName(), speaker));
            }
        }
        return List.copyOf(pairs);
    }

    private static boolean resolve(Boolean override, boolean workspaceDefault) {
        return override == null ? workspaceDefault : override;
    }

    private static SessionPeer find(List<SessionPeer> members, String peer) {
        for (SessionPeer member : members) {
            if (member.peerName().equals(peer)) {
                return member;
            }
        }
        return null;
    }
}
