package dev.dyad.memory;

import dev.dyad.core.spi.EmbedPurpose;
import dev.dyad.core.spi.Embedder;
import dev.dyad.text.BigramTextAnalyzer;
import java.util.ArrayList;
import java.util.List;

/**
 * A local embedder for running without credentials.
 *
 * <p>Hashes tokens into a bag-of-words vector, so similarity tracks lexical overlap and nothing else.
 * That is enough to exercise every code path and to make dedup and ranking behave plausibly in a
 * demo — and it is not a semantic embedder. Configuring this in production would leave recall unable
 * to connect two ways of saying the same thing, which is the entire point of the semantic signal.
 *
 * <p>Tokenisation is the bigram analyzer's, not a whitespace split. Splitting Korean on whitespace
 * makes {@code 강남의} and {@code 강남} share nothing, so a demo query about a place scores zero
 * against a conclusion about that place — which looks like a bug in the ranker rather than a limit of
 * the stand-in embedder.
 */
public final class HashingEmbedder implements Embedder {

    private static final int PROJECTIONS = 4;

    private final int dimensions;
    private final BigramTextAnalyzer analyzer = new BigramTextAnalyzer();

    public HashingEmbedder(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text, EmbedPurpose purpose) {
        float[] vector = new float[dimensions];
        for (String token : tokens(text)) {
            for (int k = 0; k < PROJECTIONS; k++) {
                vector[Math.floorMod(hash(token, k), dimensions)] += 1.0f;
            }
        }
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

    @Override
    public List<float[]> embedBatch(List<String> texts, EmbedPurpose purpose) {
        List<float[]> out = new ArrayList<>(texts.size());
        texts.forEach(text -> out.add(embed(text, purpose)));
        return out;
    }

    @Override
    public int dimensions() {
        return dimensions;
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
}
