package at.aimon.memory.store;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Map/List to and from {@code jsonb}. */
public final class Jsonb {

    private static final Logger log = LoggerFactory.getLogger(Jsonb.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private Jsonb() {
    }

    /**
     * A {@code jsonb} bind parameter for {@code JdbcClient.params(Object...)}.
     *
     * <p>Declared as {@code Object} rather than {@code PGobject} on purpose. This is the only
     * production signature in the build that named a driver type, and being {@code public} it was
     * {@code aimon-memory-store}'s ABI — which forced the postgresql dependency to be {@code api} and
     * put the driver on the compile classpath of recall, engine, api and worker, none of which may
     * touch JDBC. Narrowing the class itself is not available: {@code Jsonb} lives in
     * {@code ..store} and nine of its eleven callers in {@code ..store.repo}, so package-private
     * would not reach them. Narrowing the type it hands back does the same job — the driver is now an
     * implementation detail of this module, and {@code ModuleDependencyTest}'s
     * {@code jdbcIsConfinedToThePersistenceModule} stops being the only thing standing between an
     * accidental import and a release.
     *
     * <p>Every call site passes the result straight into {@code params(...)}, so nothing reads the
     * concrete type. Give it one back only alongside a reason the caller needs it.
     */
    public static Object of(Object value) {
        try {
            PGobject object = new PGobject();
            object.setType("jsonb");
            object.setValue(MAPPER.writeValueAsString(value == null ? Map.of() : value));
            return object;
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new StoreException("cannot serialise jsonb value", e);
        }
    }

    public static Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw unreadable("object", json, e);
        }
    }

    public static List<String> toStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, STRING_LIST_TYPE);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw unreadable("array", json, e);
        }
    }

    /**
     * The value that would not parse goes to the log, never to the caller.
     *
     * <p>{@code StoreException} is a {@code MemoryException}, and {@code ApiExceptionHandler} puts a
     * {@code MemoryException}'s message straight into the response body. So the old message answered a
     * row that will not parse by handing the row back — and these are the free-form columns:
     * {@code metadata} and {@code configuration} hold whatever a client wrote, and
     * {@code internal_metadata} is deliberately absent from {@code Dtos.SessionResponse} because
     * nothing internal belongs on the wire. A 500 that quoted it undid that.
     *
     * <p>The stored bytes are exactly what an operator needs to repair the row, so they move to ERROR
     * rather than disappearing — the whole value, uncut, plus the parser's own complaint as the cause.
     * {@code shape} says which reader failed; the response body says neither, so it cannot be used to
     * probe what a column holds.
     *
     * <p>No request reaches this today. Every writer into these columns is typed
     * {@code Map<String, Object>} or {@code List<String>}, and Postgres validates {@code jsonb} on the
     * way in, so a body that survives ingress survives the read. It fires on bytes this build did not
     * write: an operator's UPDATE, a restore, a hand-written migration. That is also when a 500 is
     * most likely to be looked at by someone who should not see the row.
     */
    private static StoreException unreadable(String shape, String json, Exception cause) {
        log.error("cannot read jsonb {} from the database: {}", shape, json, cause);
        return new StoreException("a stored value could not be read; see the server log", cause);
    }
}
