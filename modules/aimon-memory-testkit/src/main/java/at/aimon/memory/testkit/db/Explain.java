package at.aimon.memory.testkit.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Runs {@code EXPLAIN} for a query shape and returns the plan as text.
 *
 * <p>Everything happens on one connection, because the planner settings that make this test
 * meaningful are session state and the pool hands out a different connection per statement otherwise.
 *
 * <p>{@code enable_seqscan = off} is the point of the exercise. On a small table a sequential scan is
 * genuinely cheaper and Postgres is right to choose it, which would make every assertion here pass
 * for the wrong reason. Turning it off asks a different and more useful question: <em>could</em> this
 * query use the index at all? A predicate that accidentally excludes a partial index, or an ORDER BY
 * that no longer matches the operator class, shows up immediately — and those are the changes that
 * quietly turn a fast query into a table scan once the data grows.
 */
public final class Explain {

    private Explain() {
    }

    public static String plan(String sql, Object... params) {
        try (Connection connection = PostgresSupport.dataSource().getConnection()) {
            try (PreparedStatement settings = connection.prepareStatement("SET enable_seqscan = off")) {
                settings.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement("EXPLAIN " + sql)) {
                for (int i = 0; i < params.length; i++) {
                    statement.setObject(i + 1, params[i]);
                }
                StringBuilder plan = new StringBuilder();
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        plan.append(rs.getString(1)).append('\n');
                    }
                }
                return plan.toString();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("EXPLAIN failed for: " + sql, e);
        }
    }

    /** Populate and analyse, so the planner has statistics rather than its default guesses. */
    public static void analyse(String... tables) {
        try (Connection connection = PostgresSupport.dataSource().getConnection()) {
            for (String table : List.of(tables)) {
                try (PreparedStatement statement = connection.prepareStatement("ANALYZE " + table)) {
                    statement.execute();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("ANALYZE failed", e);
        }
    }
}
