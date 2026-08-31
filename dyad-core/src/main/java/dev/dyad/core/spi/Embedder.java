package dev.dyad.core.spi;

import java.util.List;

/** Text to vector. Implementations own batching, truncation and retry; callers just hand over text. */
public interface Embedder {

    float[] embed(String text, EmbedPurpose purpose);

    /**
     * Batch form. The returned list is positionally aligned with the input — an implementation that
     * splits, retries or falls back to single calls must still preserve order.
     */
    List<float[]> embedBatch(List<String> texts, EmbedPurpose purpose);

    int dimensions();
}
