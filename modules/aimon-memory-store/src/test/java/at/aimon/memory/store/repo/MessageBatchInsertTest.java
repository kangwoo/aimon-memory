package at.aimon.memory.store.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import at.aimon.memory.core.model.Message;
import at.aimon.memory.store.StoreTestBase;
import at.aimon.memory.testkit.db.PostgresSupport;

/**
 * A batch of messages is one statement, not one per message.
 *
 * <p>It was one per message. The loop was not obviously wasteful — it had already been improved once,
 * from two statements per row to one, by adding {@code RETURNING} — and what kept it a loop is that
 * {@code id} and {@code created_at} are generated and the caller needs both, which JDBC batch
 * execution cannot give back. Postgres can: a multi-row {@code INSERT … RETURNING} is a single
 * statement that returns every row.
 *
 * <p>The count is the assertion rather than a duration. A timing test would measure this machine and
 * this container, and would pass on a fast loopback for the wrong reason; the round-trip count is the
 * property that changed and it is the same number everywhere.
 */
class MessageBatchInsertTest extends StoreTestBase {

    private static final int BATCH = 100;

    @Test
    void aBatchOfAHundredIsOneRoundTripRatherThanAHundred() {
        seedSession("s1");
        peers.getOrCreate(WORKSPACE, "alice", Map.of(), Map.of());
        long start = sessions.nextSequence(WORKSPACE, "s1", BATCH);

        StatementCounter counter = new StatementCounter();
        MessageRepository counted = new MessageRepository(
                JdbcClient.create(counter.wrap(PostgresSupport.dataSource())));

        List<Message> saved = counted.insertBatch(WORKSPACE, "s1", start, batchOf(BATCH));

        assertThat(saved).hasSize(BATCH);
        assertThat(counter.prepared()).isEqualTo(1);
    }

    /**
     * The rows come back in the order they went in, and complete.
     *
     * <p>Load-bearing beyond tidiness, though not for the fan-out: {@code ObserverResolver} reads each
     * returned row's own speaker and timestamp, so a row arriving under the wrong index would still be
     * filed under the right pairs. What the order carries is the response — {@code MessageController}
     * hands {@code IngestResult.messages()} back verbatim, so this is the order the caller sees set
     * against the order it sent. The generated columns are asserted too — they are the reason this
     * could not simply become a JDBC batch.
     */
    @Test
    void theBatchComesBackInSequenceOrderWithItsGeneratedColumns() {
        seedSession("s1");
        peers.getOrCreate(WORKSPACE, "alice", Map.of(), Map.of());
        long start = sessions.nextSequence(WORKSPACE, "s1", BATCH);

        List<Message> saved = messages.insertBatch(WORKSPACE, "s1", start, batchOf(BATCH));

        assertThat(saved).hasSize(BATCH);
        for (int i = 0; i < BATCH; i++) {
            Message row = saved.get(i);
            assertThat(row.content()).isEqualTo("message " + i);
            assertThat(row.seqInSession()).isEqualTo(start + i);
            assertThat(row.tokenCount()).isEqualTo(i + 1);
            assertThat(row.metadata()).containsEntry("i", i);
            assertThat(row.id()).isPositive();
            assertThat(row.createdAt()).isNotNull();
        }
        // Ids are assigned in insertion order, so a list that is sorted by sequence is sorted by id.
        assertThat(saved).extracting(Message::id).isSorted();
        // And the table agrees with what was handed back.
        assertThat(messages.inSession(WORKSPACE, "s1", null, BATCH)).extracting(Message::content)
                .containsExactlyElementsOf(saved.stream().map(Message::content).toList());
    }

    /** An empty batch does not reach the database at all; the SQL would not be valid if it did. */
    @Test
    void anEmptyBatchIssuesNoStatement() {
        seedSession("s1");

        StatementCounter counter = new StatementCounter();
        MessageRepository counted = new MessageRepository(
                JdbcClient.create(counter.wrap(PostgresSupport.dataSource())));

        assertThat(counted.insertBatch(WORKSPACE, "s1", 1L, List.of())).isEmpty();
        assertThat(counter.prepared()).isZero();
    }

    /**
     * The sort puts the batch back in order when the database hands it back in another one.
     *
     * <p>Nothing else here can fail if {@code saved.sort(...)} is deleted. Postgres returns
     * {@code RETURNING} rows in insertion order, so against a real database the sort is a no-op and an
     * unguarded line is a line the next reader tidies away. What the javadoc actually claims is
     * narrower — that Postgres does not <em>promise</em> that order — so the way to guard it is to make
     * the database break it.
     *
     * <p>The same {@code Connection} proxy the round-trip count uses, rewriting the repository's own
     * statement into a data-modifying CTE ordered the other way. The rows arriving newest first is a
     * real result set from a real Postgres, not a stubbed list, and the assertion is the caller's
     * contract: {@code saved.get(i)} is {@code messages.get(i)}.
     */
    @Test
    void aReorderedReturningIsPutBackInSequenceOrder() {
        seedSession("s1");
        peers.getOrCreate(WORKSPACE, "alice", Map.of(), Map.of());
        long start = sessions.nextSequence(WORKSPACE, "s1", BATCH);

        MessageRepository reordering = new MessageRepository(
                JdbcClient.create(ReversedReturning.wrap(PostgresSupport.dataSource())));

        List<Message> saved = reordering.insertBatch(WORKSPACE, "s1", start, batchOf(BATCH));

        assertThat(saved).hasSize(BATCH);
        assertThat(saved).extracting(Message::seqInSession).isSorted();
        for (int i = 0; i < BATCH; i++) {
            assertThat(saved.get(i).content()).isEqualTo("message " + i);
            assertThat(saved.get(i).seqInSession()).isEqualTo(start + i);
        }
    }

    private static List<MessageRepository.NewMessage> batchOf(int size) {
        List<MessageRepository.NewMessage> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rows.add(new MessageRepository.NewMessage("alice", "message " + i, i + 1, Map.of("i", i)));
        }
        return rows;
    }

    /**
     * Counts {@code prepareStatement} calls on connections handed out by the pool.
     *
     * <p>A dynamic proxy rather than a driver-level hook: the number this test is about is how many
     * statements the repository asks for, which is exactly what the pooled {@code Connection} is asked
     * to prepare. Nothing here changes behaviour, so the repository under test is the real one.
     */
    private static final class StatementCounter {

        private final AtomicInteger prepared = new AtomicInteger();

        int prepared() {
            return prepared.get();
        }

        DataSource wrap(DataSource delegate) {
            return (DataSource) Proxy.newProxyInstance(StatementCounter.class.getClassLoader(),
                    new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                        Object result = call(delegate, method, args);
                        return result instanceof Connection connection ? wrap(connection) : result;
                    });
        }

        private Connection wrap(Connection delegate) {
            return (Connection) Proxy.newProxyInstance(StatementCounter.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement")) {
                            prepared.incrementAndGet();
                        }
                        return call(delegate, method, args);
                    });
        }

        private static Object call(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    /**
     * A database that returns the batch in the wrong order.
     *
     * <p>Wraps the {@code INSERT … RETURNING} the repository built in a data-modifying CTE and orders
     * the rows by sequence descending. The insert is unchanged — same statement, same parameters, same
     * rows written — only the order they come back in.
     */
    private static final class ReversedReturning {

        private ReversedReturning() {
        }

        static DataSource wrap(DataSource delegate) {
            return (DataSource) Proxy.newProxyInstance(ReversedReturning.class.getClassLoader(),
                    new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                        Object result = call(delegate, method, args);
                        return result instanceof Connection connection ? wrap(connection) : result;
                    });
        }

        private static Connection wrap(Connection delegate) {
            return (Connection) Proxy.newProxyInstance(ReversedReturning.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql
                                && sql.startsWith("INSERT INTO messages")) {
                            Object[] rewritten = args.clone();
                            rewritten[0] = "WITH inserted AS (" + sql
                                    + ") SELECT * FROM inserted ORDER BY seq_in_session DESC";
                            return call(delegate, method, rewritten);
                        }
                        return call(delegate, method, args);
                    });
        }

        private static Object call(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
