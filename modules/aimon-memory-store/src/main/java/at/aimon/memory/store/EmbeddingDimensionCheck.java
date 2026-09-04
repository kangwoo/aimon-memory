package at.aimon.memory.store;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import at.aimon.memory.core.MemoryException;
import at.aimon.memory.core.spi.Embedder;

/**
 * Refuses to start when the embedder and the vector columns disagree about width.
 *
 * <p>Nothing checked this, and the two are configured independently. An embedder set to 3072
 * dimensions produced its vectors happily — {@code OpenAiEmbedder} validates against the property it
 * was given, not against the database — and then every conclusion write failed on the insert, which
 * the API reported as a 409 "conflicts with existing data" and the worker turned into a work unit
 * that failed until it was quarantined. Nothing in either message said the word "dimensions".
 *
 * <p>Fresh databases now take their width from the same setting, so this is here for the case that
 * cannot: a database created at one width and reconfigured at another. Changing it needs a migration
 * that rewrites the column and re-embeds every row, which is not something to discover from a 409.
 */
@Component
@DependsOnDatabaseInitialization
public class EmbeddingDimensionCheck {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingDimensionCheck.class);

    private final JdbcClient jdbc;
    private final Embedder embedder;

    public EmbeddingDimensionCheck(JdbcClient jdbc, Embedder embedder) {
        this.jdbc = jdbc;
        this.embedder = embedder;
    }

    @PostConstruct
    public void verify() {
        check("conclusions");
        check("entities");
    }

    private void check(String table) {
        Integer declared = columnDimensions(table);
        if (declared == null) {
            // No table yet, or a build where migrations have not run. Not this component's business to
            // decide that is fatal — whatever needs the table will say so far more clearly.
            log.debug("no {}.embedding column to check yet", table);
            return;
        }
        if (declared != embedder.dimensions()) {
            throw new MemoryException("embedding_dimension_mismatch",
                    ("%s.embedding is vector(%d) but the configured embedder produces %d dimensions."
                            + " Every write would fail on the insert. Either set"
                            + " aimon.memory.embed.dimensions to %d, or migrate the column and re-embed"
                            + " every row.").formatted(table, declared, embedder.dimensions(), declared));
        }
    }

    /**
     * pgvector stores the declared width in {@code atttypmod} directly, with no offset — unlike the
     * varchar convention of length plus four. A value of -1 means the column was declared without a
     * width, which is legal for pgvector and imposes nothing to check against.
     */
    private Integer columnDimensions(String table) {
        return jdbc.sql("""
                SELECT a.atttypmod
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relname = ? AND a.attname = 'embedding'
                  AND n.nspname = current_schema() AND a.attnum > 0 AND NOT a.attisdropped
                """).param(table).query(Integer.class).optional().filter(dimensions -> dimensions > 0).orElse(null);
    }
}
