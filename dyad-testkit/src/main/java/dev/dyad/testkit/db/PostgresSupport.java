package dev.dyad.testkit.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One pgvector container for the whole JVM.
 *
 * <p>Reused rather than started per class: the image takes several seconds to become healthy, and a
 * suite that pays that once runs in a fraction of the time. Isolation comes from truncating between
 * tests, which is also faster than re-running migrations.
 */
public final class PostgresSupport {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer<?> container;
    private static DataSource dataSource;

    private PostgresSupport() {}

    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            container = new PostgreSQLContainer<>(IMAGE).withDatabaseName("dyad").withUsername("dyad").withPassword("dyad");
            container.start();
            // Pooled, like production. An unpooled source opens a connection per statement, which
            // turns a recall's handful of queries into a handful of TCP handshakes — enough to
            // dominate any latency the load profile reports and to make it describe nothing useful.
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(container.getJdbcUrl());
            config.setUsername(container.getUsername());
            config.setPassword(container.getPassword());
            config.setMaximumPoolSize(16);
            config.setPoolName("dyad-test");
            dataSource = new HikariDataSource(config);
            migrate(dataSource);
        }
        return dataSource;
    }

    public static String jdbcUrl() {
        dataSource();
        return container.getJdbcUrl();
    }

    public static String username() {
        dataSource();
        return container.getUsername();
    }

    public static String password() {
        dataSource();
        return container.getPassword();
    }

    private static void migrate(DataSource source) {
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                // The vector columns take their width from this, exactly as the applications do. The
                // suite runs on the hashing embedder, whose default is the same 1536.
                .placeholders(java.util.Map.of("embedding_dimensions", "1536"))
                .load()
                .migrate();
    }

    /**
     * Empty every table without dropping it.
     *
     * <p>{@code RESTART IDENTITY CASCADE} in one statement so the order of truncation does not have
     * to respect foreign keys, and identity columns start from 1 again, which keeps message ids
     * predictable in fixtures.
     */
    public static void truncateAll() {
        try (var connection = dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "TRUNCATE workspaces, peers, sessions, session_peers, messages, collections,"
                            + " conclusions, entities, entity_links, conclusion_events, queue,"
                            + " work_unit_claims, dreams, peer_cards RESTART IDENTITY CASCADE");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("cannot reset the test database", e);
        }
    }
}
