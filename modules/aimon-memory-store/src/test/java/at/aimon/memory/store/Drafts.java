package at.aimon.memory.store;

import java.util.List;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.ConclusionDraft;
import at.aimon.memory.core.model.ConclusionLevel;
import at.aimon.memory.core.spi.Analyzer;
import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.testkit.stub.StubAnalyzer;
import at.aimon.memory.testkit.stub.StubEmbedder;
import at.aimon.memory.text.ContentHash;
import at.aimon.memory.text.Normalizer;

/** Builds fully-prepared drafts the way the write pipeline would, so dedup sees realistic input. */
public final class Drafts {

    private static final StubAnalyzer ANALYZER = new StubAnalyzer();
    private static final StubEmbedder EMBEDDER = new StubEmbedder();

    private Drafts() {
    }

    public static ConclusionDraft explicit(PairKey pair, String session, String content) {
        return of(pair, session, content, ConclusionLevel.EXPLICIT, List.of());
    }

    /**
     * A draft indexed by a supplied analyzer instead of the stub.
     *
     * <p>{@link StubAnalyzer} splits on every non-alphanumeric, so it cannot produce the punctuated
     * tokens — {@code alice's}, {@code 50,000}, {@code note:draft} — that {@code TsQuery} has to make
     * a decision about. A test whose subject is that punctuation has to index its corpus the way
     * production does, with the same analyzer on the document side and the query side; everything
     * else here is deliberately the stub, so that the punctuation is the only thing that differs.
     */
    public static ConclusionDraft explicit(PairKey pair, String session, String content, Analyzer analyzer) {
        String norm = Normalizer.normalize(content);
        return ConclusionDraft.builder().pair(pair).sessionName(session).content(content).contentNorm(norm)
                .contentAnalyzed(analyzer.analyze(content)).contentHash(ContentHash.of(norm))
                .level(ConclusionLevel.EXPLICIT).entityNames(List.of())
                .embedding(EMBEDDER.embed(content, EmbedPurpose.DOCUMENT)).actor(Actor.DERIVER)
                .promptVersion("test-prompt-1").build();
    }

    public static ConclusionDraft of(PairKey pair, String session, String content, ConclusionLevel level,
            List<String> entities) {
        String norm = Normalizer.normalize(content);
        return ConclusionDraft.builder().pair(pair).sessionName(session).content(content).contentNorm(norm)
                .contentAnalyzed(ANALYZER.analyze(content)).contentHash(ContentHash.of(norm)).level(level)
                .entityNames(entities).embedding(EMBEDDER.embed(content, EmbedPurpose.DOCUMENT)).actor(Actor.DERIVER)
                .promptVersion("test-prompt-1").build();
    }

    public static float[] embed(String text) {
        return EMBEDDER.embed(text, EmbedPurpose.QUERY);
    }

    public static String analyze(String text) {
        return ANALYZER.analyze(text);
    }
}
