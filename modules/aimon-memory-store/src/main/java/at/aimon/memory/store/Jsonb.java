package at.aimon.memory.store;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.postgresql.util.PGobject;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Map/List to and from {@code jsonb}. */
public final class Jsonb {

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
            throw new StoreException("cannot read jsonb object: " + json, e);
        }
    }

    public static List<String> toStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, STRING_LIST_TYPE);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new StoreException("cannot read jsonb array: " + json, e);
        }
    }
}
