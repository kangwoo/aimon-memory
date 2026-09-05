package at.aimon.memory.core.spi;

import java.util.List;

import at.aimon.memory.core.MemoryException;

/** Text to vector. Implementations own batching, truncation and retry; callers just hand over text. */
public interface Embedder {

    float[] embed(String text, EmbedPurpose purpose);

    /**
     * Batch form. The returned list is positionally aligned with the input — an implementation that
     * splits, retries or falls back to single calls must still preserve order.
     */
    List<float[]> embedBatch(List<String> texts, EmbedPurpose purpose);

    int dimensions();

    /**
     * Check the alignment the contract above promises, before a caller indexes into the result.
     *
     * <p>Every caller pairs {@code texts.get(i)} with {@code vectors.get(i)}, and that contract was
     * the only thing making it safe — nothing enforced it. A short list surfaced as an
     * IndexOutOfBoundsException partway through the loop, and where that loop was
     * {@code ConclusionWriter.write} the damage outlived the exception: the conclusions before the
     * failure were already committed in their own transactions, none of their entity edges had been
     * written yet, and the message named neither the embedder nor the batch. Failing before the loop
     * makes the whole batch retryable, which is what the queue already knows how to do with it.
     *
     * <p>Here rather than at each of the four call sites, because this is the interface that states
     * the promise, and a check that lives next to the promise is one a new implementation cannot be
     * written without meeting.
     *
     * @return {@code vectors}, so a caller can wrap the call it already makes
     */
    static List<float[]> requireAligned(List<String> texts, List<float[]> vectors) {
        if (vectors.size() != texts.size()) {
            throw new MemoryException("embedding_batch_misaligned",
                    "the embedder returned " + vectors.size() + " vectors for " + texts.size()
                            + " inputs; a batch has to come back positionally aligned with its input");
        }
        return vectors;
    }
}
