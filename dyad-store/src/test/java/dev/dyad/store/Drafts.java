package dev.dyad.store;

import dev.dyad.core.key.PairKey;
import dev.dyad.core.model.Actor;
import dev.dyad.core.model.ConclusionDraft;
import dev.dyad.core.model.ConclusionLevel;
import dev.dyad.core.spi.EmbedPurpose;
import dev.dyad.testkit.stub.StubAnalyzer;
import dev.dyad.testkit.stub.StubEmbedder;
import dev.dyad.text.ContentHash;
import dev.dyad.text.Normalizer;
import java.util.List;

/** Builds fully-prepared drafts the way the write pipeline would, so dedup sees realistic input. */
public final class Drafts {

    private static final StubAnalyzer ANALYZER = new StubAnalyzer();
    private static final StubEmbedder EMBEDDER = new StubEmbedder();

    private Drafts() {}

    public static ConclusionDraft explicit(PairKey pair, String session, String content) {
        return of(pair, session, content, ConclusionLevel.EXPLICIT, List.of());
    }

    public static ConclusionDraft of(
            PairKey pair, String session, String content, ConclusionLevel level, List<String> entities) {
        String norm = Normalizer.normalize(content);
        return ConclusionDraft.builder()
                .pair(pair)
                .sessionName(session)
                .content(content)
                .contentNorm(norm)
                .contentAnalyzed(ANALYZER.analyze(content))
                .contentHash(ContentHash.of(norm))
                .level(level)
                .entityNames(entities)
                .embedding(EMBEDDER.embed(content, EmbedPurpose.DOCUMENT))
                .actor(Actor.DERIVER)
                .promptVersion("test-prompt-1")
                .build();
    }

    public static float[] embed(String text) {
        return EMBEDDER.embed(text, EmbedPurpose.QUERY);
    }

    public static String analyze(String text) {
        return ANALYZER.analyze(text);
    }
}
