package at.aimon.memory.engine.fanout;

import java.util.ArrayList;
import java.util.List;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Message;
import at.aimon.memory.core.model.SessionPeer;
import at.aimon.memory.store.WorkspaceSettings;

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
 * <p>Every pair this returns becomes its own work unit, its own batch and its own extraction call —
 * fan-out is over model calls, not only over storage. That is deliberate (ADR 0006): the extraction
 * prompt is written from the observer's side, so two observers of the same messages are asking two
 * different questions. It is also the cost model to know before opening a large room, since a
 * session of N mutually-observing peers produces N + N(N−1) pairs; {@code observe_others} is the
 * lever that turns the quadratic term off.
 *
 * <p>Only peers whose membership window covers the message are considered. Someone who joined an hour
 * later did not hear it, and their memory should not contain it.
 */
public final class ObserverResolver {

    private ObserverResolver() {
    }

    public static List<PairKey> observersOf(Message message, List<SessionPeer> members, WorkspaceSettings settings) {
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
