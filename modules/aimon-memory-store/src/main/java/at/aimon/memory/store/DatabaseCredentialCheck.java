package at.aimon.memory.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import at.aimon.memory.core.MemoryException;

/**
 * Refuses to start when the database password committed to this repository is in use against a
 * database that is not on this machine.
 *
 * <p>{@code JwtService} makes the argument this is built on, and it applies word for word to
 * {@code AIMON_MEMORY_DB_PASSWORD}: a credential with a default in {@code application.yml} is a
 * credential published in the repository, a deployment that forgets the environment variable starts
 * cleanly with it, and nothing in the running system distinguishes that state from a correct one.
 *
 * <p>It is not enforced the same way because the two credentials fail differently. A signing key is
 * useful to anyone who can reach the API; a database password is useful only to someone who can
 * already reach the database. And there is one deployment where the committed value is genuinely the
 * right answer — the local compose stack, which ships the matching {@code POSTGRES_PASSWORD} so that
 * {@code docker compose up} followed by {@code java -jar} works with no environment set at all.
 * Dropping the default outright would break that flow, and the file that would have to change to
 * keep it working is a compose file, which no application property can reach.
 *
 * <p>So the refusal is scoped to the case that has no defence: the committed default in use against
 * a remote database. That is the production accident the JWT reasoning is about, and it is refused
 * at startup rather than warned about, for the reason given there — a warning in a log nobody reads
 * during a deploy is indistinguishable from no warning at all.
 */
@Component
public class DatabaseCredentialCheck {

    /** The value committed in both applications' {@code application.yml}, and in docker-compose.yml. */
    private static final String SHIPPED_DEFAULT = "aimon_memory";

    /**
     * Hosts that mean "this machine". The compose stack publishes Postgres on the loopback interface,
     * and Testcontainers maps to it too, so the local flows this exists to protect all land here.
     */
    private static final List<String> LOCAL_HOSTS = List.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final String url;
    private final String password;

    public DatabaseCredentialCheck(@Value("${spring.datasource.url:}") String url,
            @Value("${spring.datasource.password:}") String password) {
        this.url = url;
        this.password = password;
    }

    @PostConstruct
    public void verify() {
        if (!SHIPPED_DEFAULT.equals(password) || isLocal(url)) {
            return;
        }
        throw new MemoryException("default_db_password",
                "spring.datasource.password is still the value committed in application.yml, and"
                        + " spring.datasource.url points at a database that is not on this host."
                        + " Anyone who has read this repository knows that password. Export"
                        + " AIMON_MEMORY_DB_PASSWORD with the real one before starting.");
    }

    /**
     * Whether the JDBC URL names this machine, and nothing but this machine.
     *
     * <p>Read off the string rather than through {@code URI}: {@code jdbc:postgresql://…} is not a URI
     * Java parses usefully — the scheme is {@code jdbc} and everything that matters is in its opaque
     * part. A URL with no host at all is a Unix socket or an embedded database, which is local by
     * construction, so an unparseable one is treated as local rather than as a reason to refuse.
     *
     * <p><b>Every</b> host, not the first one. The pgjdbc URL takes a comma-separated failover list,
     * and reading only the head of it left the exact combination this class exists to refuse
     * reachable: {@code jdbc:postgresql://localhost:5432,prod-db:5432/aimon_memory} with the committed
     * password started cleanly, because the first host answered the question and the second one — the
     * production database this is about — was never asked. A list is local only if all of it is.
     */
    static boolean isLocal(String jdbcUrl) {
        List<String> hosts = hostsOf(jdbcUrl);
        return hosts.isEmpty() || hosts.stream().allMatch(LOCAL_HOSTS::contains);
    }

    private static List<String> hostsOf(String jdbcUrl) {
        if (jdbcUrl == null) {
            return List.of();
        }
        int start = jdbcUrl.indexOf("//");
        if (start < 0) {
            return List.of();
        }
        String authority = jdbcUrl.substring(start + 2);
        // The authority ends at the database path or the query string. Not at a comma any more: that
        // is a separator *inside* the authority, and treating it as the end is what hid every host
        // after the first.
        for (int i = 0; i < authority.length(); i++) {
            char c = authority.charAt(i);
            if (c == '/' || c == '?') {
                authority = authority.substring(0, i);
                break;
            }
        }
        // Credentials come off before the split, not after. pgjdbc writes them once, ahead of the
        // whole list, so a per-entry strip would look for them in the wrong place — and a password
        // containing a comma would be split into fake hosts.
        int credentials = authority.lastIndexOf('@');
        if (credentials >= 0) {
            authority = authority.substring(credentials + 1);
        }
        List<String> hosts = new ArrayList<>();
        for (String entry : authority.split(",")) {
            String host = hostOf(entry.strip());
            // A blank entry is a trailing or doubled comma, which names no host. Skipped rather than
            // read as one, so a typo in the URL is not silently a refusal to start.
            if (!host.isEmpty()) {
                hosts.add(host);
            }
        }
        return hosts;
    }

    private static String hostOf(String hostAndPort) {
        if (hostAndPort.startsWith("[")) {
            // An IPv6 literal keeps its brackets, which is how the URL writes it and how LOCAL_HOSTS
            // spells it; only the port after the closing bracket comes off.
            int close = hostAndPort.indexOf(']');
            return close < 0 ? hostAndPort : hostAndPort.substring(0, close + 1);
        }
        int port = hostAndPort.indexOf(':');
        return (port < 0 ? hostAndPort : hostAndPort.substring(0, port)).toLowerCase(Locale.ROOT);
    }
}
