package at.aimon.memory.store;

import java.util.ArrayList;
import java.util.List;

/**
 * float[] to and from pgvector's text form.
 *
 * <p>The literal is bound as a string and cast with {@code ?::vector} at every call site, which
 * avoids registering a JDBC type mapping on every pooled connection. Vectors are written once per
 * conclusion and read back only by the reconciler, so the text round trip costs nothing that matters.
 */
public final class Vectors {

    private Vectors() {
    }

    public static String toLiteral(float[] vector) {
        if (vector == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(vector.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    public static float[] parse(String literal) {
        if (literal == null || literal.isBlank()) {
            return null;
        }
        String body = literal.trim();
        if (body.startsWith("[") && body.endsWith("]")) {
            body = body.substring(1, body.length() - 1);
        }
        if (body.isBlank()) {
            return new float[0];
        }
        List<Float> values = new ArrayList<>();
        for (String part : body.split(",")) {
            values.add(Float.parseFloat(part.trim()));
        }
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /** Cosine similarity in [0,1] from pgvector's cosine distance. */
    public static double similarityFromDistance(double cosineDistance) {
        double similarity = 1.0 - cosineDistance;
        if (similarity < 0.0) {
            return 0.0;
        }
        return Math.min(similarity, 1.0);
    }
}
