package at.aimon.memory.store.repo;

import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import at.aimon.memory.core.key.PairKey;

/**
 * The (observer, observed) pair row.
 *
 * <p>Its only real job is to be the target of the composite foreign key on {@code conclusions}. That
 * is worth a table: without it the pair scope is three unconstrained text columns, and one typo in
 * one insert path files a memory under a pair that does not exist.
 */
@Repository
public class CollectionRepository {

    private final JdbcClient jdbc;

    public CollectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void getOrCreate(PairKey pair) {
        jdbc.sql("""
                INSERT INTO collections (workspace_name, observer, observed)
                VALUES (?, ?, ?)
                ON CONFLICT (observer, observed, workspace_name) DO NOTHING
                """).params(pair.workspaceName(), pair.observer(), pair.observed()).update();
    }

    public record DreamState(Instant lastDreamAt, int explicitAtLastDream) {
    }

    public Optional<DreamState> dreamState(PairKey pair) {
        return jdbc
                .sql("SELECT last_dream_at, explicit_at_last_dream FROM collections"
                        + " WHERE workspace_name = ? AND observer = ? AND observed = ?")
                .params(pair.workspaceName(), pair.observer(), pair.observed())
                .query((rs, i) -> new DreamState(
                        rs.getTimestamp("last_dream_at") == null ? null : rs.getTimestamp("last_dream_at").toInstant(),
                        rs.getInt("explicit_at_last_dream")))
                .optional();
    }

    public void markDreamed(PairKey pair, int explicitCount) {
        jdbc.sql("UPDATE collections SET last_dream_at = now(), explicit_at_last_dream = ?"
                + " WHERE workspace_name = ? AND observer = ? AND observed = ?")
                .params(explicitCount, pair.workspaceName(), pair.observer(), pair.observed()).update();
    }
}
