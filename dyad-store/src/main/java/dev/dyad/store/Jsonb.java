package dev.dyad.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.postgresql.util.PGobject;

/** Map/List to and from {@code jsonb}. */
public final class Jsonb {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private Jsonb() {}

    public static PGobject of(Object value) {
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
