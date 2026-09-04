package at.aimon.memory.store.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.store.Jsonb;

/**
 * The peer card: a short, wholly-replaced summary of who someone is, per pair.
 *
 * <p>Replaced rather than appended for a reason. An accumulating card drifts — old lines stay true
 * enough to keep, contradictory lines pile up, and it grows past the point where a model reads all of
 * it. Regenerating the whole thing from current conclusions keeps it honest and bounded.
 */
@Repository
public class PeerCardRepository {

    private final JdbcClient jdbc;

    public PeerCardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record PeerCard(PairKey pair, List<String> lines, Instant updatedAt) {
    }

    public Optional<PeerCard> find(PairKey pair) {
        return jdbc
                .sql("SELECT lines, updated_at FROM peer_cards"
                        + " WHERE workspace_name = ? AND observer = ? AND observed = ?")
                .params(pair.workspaceName(), pair.observer(), pair.observed()).query((rs, i) -> new PeerCard(pair,
                        Jsonb.toStringList(rs.getString("lines")), rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    public void replace(PairKey pair, List<String> lines) {
        jdbc.sql("""
                INSERT INTO peer_cards (workspace_name, observer, observed, lines, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (workspace_name, observer, observed)
                DO UPDATE SET lines = EXCLUDED.lines, updated_at = now()
                """).params(pair.workspaceName(), pair.observer(), pair.observed(), Jsonb.of(lines)).update();
    }
}
