package at.aimon.memory.testkit.stub;

import java.util.ArrayList;
import java.util.List;

import at.aimon.memory.core.spi.Analyzer;
import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.core.spi.Embedder;
import at.aimon.memory.text.BigramTextAnalyzer;

/**
 * A deterministic embedder for tests.
 *
 * <p>Not random noise: it hashes tokens into a bag-of-words vector and L2-normalises, so cosine
 * similarity actually tracks token overlap. That matters because dedup stage 3 and the {@code sem}
 * signal are both threshold-based, and an embedder whose similarities are meaningless would let
 * those tests pass while asserting nothing.
 *
 * <p>Tokenisation goes through an {@link Analyzer} rather than a whitespace split. On Korean a
 * whitespace split makes {@code 강남의} and {@code 강남} entirely unrelated, so a test would show a
 * similarity of zero between two texts about the same place and nobody would notice the stub was
 * lying rather than the code being wrong. Character bigrams over CJK give the overlap a real shape.
 *
 * <p>The same text always produces the same vector, which is what makes recall fixtures stable.
 */
public final class StubEmbedder implements Embedder {

    private static final int PROJECTIONS = 4;

    private final int dimensions;
    private final Analyzer analyzer;
    private int calls;

    public StubEmbedder() {
        this(1536);
    }

    public StubEmbedder(int dimensions) {
        this(dimensions, new BigramTextAnalyzer());
    }

    public StubEmbedder(int dimensions, Analyzer analyzer) {
        this.dimensions = dimensions;
        this.analyzer = analyzer;
    }

    @Override
    public float[] embed(String text, EmbedPurpose purpose) {
        calls++;
        float[] vector = new float[dimensions];
        for (String token : tokens(text)) {
            for (int k = 0; k < PROJECTIONS; k++) {
                int index = Math.floorMod(hash(token, k), dimensions);
                vector[index] += 1.0f;
            }
        }
        return normalise(vector);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts, EmbedPurpose purpose) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(embed(text, purpose));
        }
        return out;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    public int calls() {
        return calls;
    }

    private List<String> tokens(String text) {
        List<String> tokens = analyzer.tokens(text);
        return tokens.isEmpty() ? List.of("empty") : tokens;
    }

    private static int hash(String token, int seed) {
        int h = 0x811C9DC5 ^ (seed * 0x01000193);
        for (int i = 0; i < token.length(); i++) {
            h = (h ^ token.charAt(i)) * 0x01000193;
        }
        return h;
    }

    private static float[] normalise(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        if (sum == 0) {
            vector[0] = 1.0f;
            return vector;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
        return vector;
    }
}
