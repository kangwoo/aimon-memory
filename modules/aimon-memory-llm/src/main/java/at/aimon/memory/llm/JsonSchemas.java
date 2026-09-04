package at.aimon.memory.llm;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Derives a strict JSON Schema from a Java record.
 *
 * <p>Small on purpose: it covers strings, numbers, booleans, enums, lists and nested records, which
 * is everything the extraction schemas use. Anything else throws rather than emitting a permissive
 * schema — a schema that quietly accepts more than intended is worse than no schema, because the
 * parse failure then happens far from the cause.
 *
 * <p>{@code additionalProperties: false} and a {@code required} list naming every property are both
 * mandatory for strict mode on OpenAI; optionality is expressed as a nullable type, not omission.
 */
public final class JsonSchemas {

    private JsonSchemas() {
    }

    public static String forRecord(Class<?> type) {
        return schemaFor(type).toString();
    }

    private static ObjectNode schemaFor(Class<?> type) {
        if (!type.isRecord()) {
            throw new LlmException("bad_schema", type.getName() + " is not a record");
        }
        ObjectNode node = Json.object();
        node.put("type", "object");
        ObjectNode properties = node.putObject("properties");
        ArrayNode required = Json.object().arrayNode();
        for (RecordComponent component : type.getRecordComponents()) {
            properties.set(component.getName(), typeNode(component.getGenericType(), component.getType()));
            required.add(component.getName());
        }
        node.set("required", required);
        node.put("additionalProperties", false);
        return node;
    }

    private static ObjectNode typeNode(Type generic, Class<?> raw) {
        ObjectNode node = Json.object();
        if (raw == String.class) {
            node.put("type", "string");
        } else if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class) {
            node.put("type", "integer");
        } else if (raw == double.class || raw == Double.class || raw == float.class || raw == Float.class) {
            node.put("type", "number");
        } else if (raw == boolean.class || raw == Boolean.class) {
            node.put("type", "boolean");
        } else if (raw.isEnum()) {
            node.put("type", "string");
            ArrayNode values = node.putArray("enum");
            for (Object constant : raw.getEnumConstants()) {
                values.add(((Enum<?>) constant).name().toLowerCase(java.util.Locale.ROOT));
            }
        } else if (List.class.isAssignableFrom(raw)) {
            node.put("type", "array");
            Class<?> element = elementType(generic);
            node.set("items", typeNode(element, element));
        } else if (raw.isRecord()) {
            return schemaFor(raw);
        } else {
            throw new LlmException("bad_schema", "unsupported schema type: " + raw.getName());
        }
        return node;
    }

    private static Class<?> elementType(Type generic) {
        if (generic instanceof ParameterizedType parameterized) {
            Type argument = parameterized.getActualTypeArguments()[0];
            if (argument instanceof Class<?> cls) {
                return cls;
            }
            if (argument instanceof ParameterizedType nested && nested.getRawType() instanceof Class<?> cls) {
                return cls;
            }
        }
        throw new LlmException("bad_schema", "list element type is not resolvable: " + generic);
    }
}
