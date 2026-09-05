package at.aimon.memory.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.MemoryException;

/**
 * Which deployments the committed database password is allowed to start.
 *
 * <p>The rule is one line — the value in {@code application.yml} may only be used against a database
 * on this host — and the whole of it lives in how a JDBC URL is read. That made the URL parsing the
 * thing worth testing, and it had no test: the check shipped deciding "is this host local?" from the
 * head of a comma-separated failover list, so
 * {@code jdbc:postgresql://localhost:5432,prod-db:5432/aimon_memory} was read as local and the
 * production database behind the second entry started with a password anyone who has read this
 * repository knows. A guard that is wrong about which deployment it is looking at is worse than no
 * guard, because it is also reassuring.
 */
class DatabaseCredentialCheckTest {

    /** The value committed in both applications' {@code application.yml} and in docker-compose.yml. */
    private static final String SHIPPED = "aimon_memory";

    private static void verify(String url, String password) {
        new DatabaseCredentialCheck(url, password).verify();
    }

    @Test
    void theCommittedPasswordAgainstARemoteDatabaseRefusesToStart() {
        assertThatThrownBy(() -> verify("jdbc:postgresql://prod-db.internal:5432/aimon_memory", SHIPPED))
                .isInstanceOf(MemoryException.class).hasMessageContaining("AIMON_MEMORY_DB_PASSWORD");
    }

    /**
     * The local flow the README documents: {@code docker compose up}, then {@code java -jar}, with no
     * environment set at all. Compose publishes Postgres on the loopback interface, so this is the
     * deployment the default exists for and it has to keep working.
     */
    @Test
    void theCommittedPasswordAgainstThisMachineIsFine() {
        assertThatCode(() -> verify("jdbc:postgresql://localhost:5432/aimon_memory", SHIPPED))
                .doesNotThrowAnyException();
        assertThatCode(() -> verify("jdbc:postgresql://127.0.0.1:5432/aimon_memory", SHIPPED))
                .doesNotThrowAnyException();
        assertThatCode(() -> verify("jdbc:postgresql://[::1]:5432/aimon_memory", SHIPPED)).doesNotThrowAnyException();
    }

    /**
     * A failover list is local only if all of it is.
     *
     * <p>The case this check was letting through. pgjdbc takes {@code host:port,host:port}, and reading
     * only the first entry answered the question with the one host that was never the risk.
     */
    @Test
    void aFailoverListWithOneRemoteHostRefusesToStart() {
        assertThatThrownBy(() -> verify("jdbc:postgresql://localhost:5432,prod-db:5432/aimon_memory", SHIPPED))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> verify("jdbc:postgresql://prod-db:5432,localhost:5432/aimon_memory", SHIPPED))
                .isInstanceOf(MemoryException.class);
    }

    /** Two loopback entries are still two loopback entries, so a local failover pair starts. */
    @Test
    void aFailoverListThatIsEntirelyLocalIsFine() {
        assertThat(DatabaseCredentialCheck.isLocal("jdbc:postgresql://localhost:5432,127.0.0.1:5433/aimon_memory"))
                .isTrue();
    }

    /**
     * Credentials in the URL come off before the list is split, not after.
     *
     * <p>pgjdbc writes them once, ahead of the whole list. Stripping per entry would look for them in
     * the wrong place and read {@code user:pass@localhost} as a host named after the password.
     */
    @Test
    void credentialsInTheUrlDoNotBecomeAHost() {
        assertThat(DatabaseCredentialCheck.isLocal("jdbc:postgresql://someone:secret@localhost:5432,127.0.0.1/db"))
                .isTrue();
        assertThat(DatabaseCredentialCheck.isLocal("jdbc:postgresql://someone:secret@prod-db:5432/db")).isFalse();
    }

    /**
     * A URL naming no host is a Unix socket or an embedded database, which is local by construction.
     * Treated as local rather than as a reason to refuse, so an unparseable URL does not stop a
     * deployment the check has nothing to say about.
     */
    @Test
    void aUrlWithNoHostIsLocal() {
        assertThat(DatabaseCredentialCheck.isLocal("jdbc:postgresql:aimon_memory")).isTrue();
        assertThat(DatabaseCredentialCheck.isLocal(null)).isTrue();
        assertThatCode(() -> verify("", SHIPPED)).doesNotThrowAnyException();
    }

    /** A trailing comma names no host, and must not be read as one. */
    @Test
    void aBlankEntryInTheListIsNotAHost() {
        assertThat(DatabaseCredentialCheck.isLocal("jdbc:postgresql://localhost:5432,/aimon_memory")).isTrue();
    }

    /** Host matching ignores case, the way a hostname does. */
    @Test
    void hostNamesAreCaseInsensitive() {
        assertThat(DatabaseCredentialCheck.isLocal("jdbc:postgresql://LOCALHOST:5432/aimon_memory")).isTrue();
    }

    /**
     * A real password is the operator's business wherever the database is.
     *
     * <p>The refusal is scoped to the one credential this repository published; it is not an opinion
     * about remote databases.
     */
    @Test
    void aPasswordThatIsNotTheCommittedOneIsNeverTheChecksBusiness() {
        assertThatCode(() -> verify("jdbc:postgresql://prod-db.internal:5432/aimon_memory", "a-real-secret"))
                .doesNotThrowAnyException();
        assertThatCode(() -> verify("jdbc:postgresql://prod-db.internal:5432/aimon_memory", ""))
                .doesNotThrowAnyException();
    }
}
