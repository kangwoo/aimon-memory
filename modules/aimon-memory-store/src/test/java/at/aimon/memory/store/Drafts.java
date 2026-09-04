package at.aimon.memory.store;

import java.util.List;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.ConclusionDraft;
import at.aimon.memory.core.model.ConclusionLevel;
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
