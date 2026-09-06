package at.aimon.memory.recall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.memory.core.config.RecallSettings;
import at.aimon.memory.core.filter.Filter;
import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.spi.ConclusionStore;
import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.core.spi.Embedder;
import at.aimon.memory.core.spi.EntityStore;
import at.aimon.memory.store.WorkspaceSettings;
import at.aimon.memory.store.WorkspaceSettingsService;
import at.aimon.memory.testkit.stub.StubAnalyzer;

/**
 * The number that decides what a recall costs is {@code limit × oversample}, and it is now bounded.
 *
 * <p>It was not. Each factor was bounded on its own — the limit at a hundred by
 * {@link RecallRequest#MAX_LIMIT}, the multiplier at a hundred by the configuration validator — and
 * nothing looked at the product, so a workspace with {@code recall.oversample: 100} answering a
 * {@code limit: 100} query pulled ten thousand rows down each of the two signal paths. That is the
 * cost the {@code Bounds} javadoc warns about ("worse than linear because the ranker oversamples each
 * signal path by a multiple of it") with the multiplier itself left open.
 *
 * <p>Stubbed rather than run against a corpus, because the assertion is about the argument handed to
 * the store and not about what comes back. A database test would need ten thousand seeded rows to say
 * the same thing, and it would say it in {@code integrationTest} where this belongs in the fast tier
 * beside the other bounds.
 */
class RecallCandidateBoundTest {

    private static final String WORKSPACE = "ws";
    private static final PairKey PAIR = new PairKey(WORKSPACE, "alice", "alice");

    private final ConclusionStore conclusions = mock(ConclusionStore.class);
    private final EntityStore entities = mock(EntityStore.class);
    private final WorkspaceSettingsService settings = mock(WorkspaceSettingsService.class);
    private final Embedder embedder = mock(Embedder.class);

    private RecallService serviceWithOversample(int oversample) {
        RecallSettings recall = new RecallSettings(RecallSettings.DEFAULT.weights(),
                RecallSettings.DEFAULT.halfLifeDays(), RecallSettings.DEFAULT.threshold(), oversample,
                RecallSettings.DEFAULT.entityTopK(), RecallSettings.DEFAULT.entitySimCut());
        WorkspaceSettings workspace = new WorkspaceSettings("und", recall, WorkspaceSettings.DEFAULT.dedup(),
                WorkspaceSettings.DEFAULT.batch(), true, true);

        when(settings.forWorkspace(WORKSPACE)).thenReturn(workspace);
        when(settings.analyzerFor(WORKSPACE)).thenReturn(new StubAnalyzer());
        when(embedder.embed(anyString(), any(EmbedPurpose.class))).thenReturn(new float[]{1.0f, 0.0f});
        // Empty on both paths: `recall` returns early, which is all this test needs. What it asserts
        // is the fetch width the paths were asked for, not what they found.
        when(conclusions.semantic(any(), any(), anyInt(), any())).thenReturn(List.of());
        when(conclusions.keyword(any(), anyString(), anyInt(), any())).thenReturn(List.of());

        return new RecallService(conclusions, entities, settings, embedder,
                Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneOffset.UTC));
    }

    /** The combination that used to fetch ten thousand rows twice. */
    @Test
    void theProductOfTheLimitAndTheOversampleIsCapped() {
        RecallService recall = serviceWithOversample(100);

        recall.recall(new RecallRequest(PAIR, "alice", RecallRequest.MAX_LIMIT, Filter.ALL, null, false));

        ArgumentCaptor<Integer> semanticWidth = ArgumentCaptor.forClass(Integer.class);
        verify(conclusions).semantic(eq(PAIR), any(), semanticWidth.capture(), any());
        assertThat(semanticWidth.getValue()).isEqualTo(RecallService.MAX_CANDIDATES);

        // Both paths, because the product is paid twice — this is the half that made ten thousand
        // into twenty.
        ArgumentCaptor<Integer> keywordWidth = ArgumentCaptor.forClass(Integer.class);
        verify(conclusions).keyword(eq(PAIR), anyString(), keywordWidth.capture(), any());
        assertThat(keywordWidth.getValue()).isEqualTo(RecallService.MAX_CANDIDATES);
    }

    /**
     * Clamped, not rejected — and the clamp engages only where the product is over the ceiling.
     *
     * <p>{@code oversample: 100} is still a legal, effective setting: at the default limit of ten it
     * asks for exactly a thousand candidates and gets them. That is why the configuration validator
     * still accepts the value it always accepted, and why nothing here is a 422. A product ceiling
     * cannot be expressed at the write boundary, because {@code bounded()} sees a multiplier and not
     * the limit it will be multiplied by.
     */
    @Test
    void aHighOversampleIsStillHonouredBelowTheCeiling() {
        RecallService recall = serviceWithOversample(100);

        recall.recall(new RecallRequest(PAIR, "alice", RecallRequest.DEFAULT_LIMIT, Filter.ALL, null, false));

        ArgumentCaptor<Integer> width = ArgumentCaptor.forClass(Integer.class);
        verify(conclusions).semantic(eq(PAIR), any(), width.capture(), any());
        assertThat(width.getValue()).isEqualTo(RecallRequest.DEFAULT_LIMIT * 100).isEqualTo(1_000);
    }

    /** And the defaults are nowhere near it: ten by four is forty, twenty-five times below the cap. */
    @Test
    void theDefaultSettingsAreUnaffected() {
        RecallService recall = serviceWithOversample(RecallSettings.DEFAULT.oversample());

        recall.recall(RecallRequest.of(PAIR, "alice"));

        ArgumentCaptor<Integer> width = ArgumentCaptor.forClass(Integer.class);
        verify(conclusions).semantic(eq(PAIR), any(), width.capture(), any());
        assertThat(width.getValue()).isEqualTo(40);
        assertThat(width.getValue()).isLessThan(RecallService.MAX_CANDIDATES);
    }
}
