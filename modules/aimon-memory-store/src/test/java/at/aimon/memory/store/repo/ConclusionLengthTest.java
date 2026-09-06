package at.aimon.memory.store.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.key.PairKey;
import at.aimon.memory.core.model.Actor;
import at.aimon.memory.core.model.ConclusionDraft;
import at.aimon.memory.core.model.ConclusionLevel;
import at.aimon.memory.core.model.DedupOutcome;
import at.aimon.memory.store.Drafts;
import at.aimon.memory.store.StoreTestBase;
import at.aimon.memory.text.BigramTextAnalyzer;
import at.aimon.memory.text.ContentHash;

/**
 * How long a conclusion is must not decide whether it can be stored — at any length this API accepts.
 *
 * <p>It used to. {@code ix_concl_norm} was a btree over {@code (workspace_name, observer, observed,
 * content_norm)}, a btree entry cannot exceed 2704 bytes on an 8 KB page, and so PostgreSQL refused
 * the row outright: {@code index row size 2728 exceeds btree version 4 maximum 2704}, SQLSTATE 54000.
 * Spring translates class 54 to {@code DataAccessResourceFailureException}, which is not a constraint
 * violation, so through the API it surfaced as {@code internal_error} and through the deriver it left a
 * half-written batch to be retried. {@code V13__conclusion_norm_hash_index.sql} indexes
 * {@code md5(content_norm)} instead, so the entry no longer carries the content.
 *
 * <p><b>Incompressibility is load-bearing here, not decoration.</b> {@code index_form_tuple} compresses
 * an attribute before it measures the entry, so the boundary is a range rather than a number, and a
 * byte width rather than a character count. Both halves of that were measured with V13 reverted.
 * Nothing these generators produce folds: every refusal is the text's own bytes plus tuple overhead —
 * 900 Hangul refused at 2728 for 2700 bytes of text, 2680 Latin at 2712, the long-name case at 3928 for
 * 1200 bytes of names and 2700 of text, and 32 000 Hangul not reaching the width check at all but
 * refused as {@code index row requires 96032 bytes, maximum size is 8191}, again its own 96 000. And
 * text that does fold passes everything: on the same reverted schema {@code "가".repeat(n)} stored at
 * 900, at 2680 and at 32 000 characters. So replacing one generator with a repeated syllable would
 * leave every case below green and none of them proving anything.
 */
class ConclusionLengthTest extends StoreTestBase {

    /**
     * The first length the old index refused, from the boundary measured through the API: 890 Hangul
     * characters stored and 900 did not.
     */
    private static final int FIRST_REFUSED_HANGUL = 900;

    /** The same boundary in one-byte characters: 2650 stored and 2680 did not. */
    private static final int FIRST_REFUSED_LATIN = 2680;

    private PairKey pair;

    private PairKey pair() {
        if (pair == null) {
            pair = seedPair("alice", "alice");
            seedSession("s1");
        }
        return pair;
    }

    @Test
    void aConclusionPastTheOldBtreeCeilingIsStoredAndReadsBackWhole() {
        String content = hangul(FIRST_REFUSED_HANGUL);

        DedupOutcome outcome = conclusions.upsert(Drafts.explicit(pair(), "s1", content));

        assertThat(outcome.kind()).isEqualTo(DedupOutcome.Kind.INSERTED);
        assertThat(conclusions.find(WORKSPACE, outcome.conclusionId()).orElseThrow().content()).isEqualTo(content);
    }

    /**
     * The same boundary reached with one-byte characters instead of three-byte ones.
     *
     * <p>Both numbers are in the same entry-width band — the old index refused 900 Hangul characters
     * and 2680 Latin ones — so a fix that happened to be about the character count rather than the
     * byte count would pass one of these and fail the other.
     */
    @Test
    void aLatinConclusionPastTheOldBtreeCeilingIsStored() {
        String content = incompressibleLatin(FIRST_REFUSED_LATIN);

        DedupOutcome outcome = conclusions.upsert(Drafts.explicit(pair(), "s1", content));

        assertThat(outcome.kind()).isEqualTo(DedupOutcome.Kind.INSERTED);
        assertThat(conclusions.find(WORKSPACE, outcome.conclusionId()).orElseThrow().content()).isEqualTo(content);
    }

    /**
     * The far side of the ceiling the API now publishes, analysed the way production analyses.
     *
     * <p>{@code content_analyzed} is what reaches {@code ix_concl_fts}, and a tsvector has a ceiling of
     * its own that this test is the guard against walking into: the raw text is the trivially-passing
     * case, because {@code to_tsvector} answers an over-long single word with a NOTICE and drops it.
     * The string the index actually sees is the analyzer's output — {@link BigramTextAnalyzer} emits a
     * bigram per character over a Hangul run, so about seven bytes of indexed text per character of
     * input — and it is that, at the published ceiling, which has to insert.
     */
    @Test
    void aConclusionAtThePublishedCeilingIsStoredWithItsAnalyzedFormIndexed() {
        String content = hangul(32_000);
        String analyzed = new BigramTextAnalyzer().analyze(content);
        assertThat(analyzed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(200_000);

        DedupOutcome outcome = conclusions
                .upsert(draft(pair(), "s1", ConclusionLevel.EXPLICIT, content, analyzed, ContentHash.of(content)));

        assertThat(outcome.kind()).isEqualTo(DedupOutcome.Kind.INSERTED);
        assertThat(conclusions.find(WORKSPACE, outcome.conclusionId()).orElseThrow().content()).isEqualTo(content);
    }

    /**
     * The half of the failure no cap on {@code CreateConclusion.content} could ever have reached.
     *
     * <p>The old entry was the content plus the three scope columns, and {@code observer} and
     * {@code observed} are unbounded fields of the same request body: a short conclusion written into a
     * pair with long names failed exactly the same way. 1200 bytes of names, which is the acceptance
     * case, split between the two peers — {@link StoreTestBase#seedPair} fixes {@code workspace_name}
     * to {@code ws}, so the names are where the bytes have to come from.
     *
     * <p>Not a promise that any pair of names works: the same three columns are still btree keys in
     * {@code ix_concl_pair}, {@code ix_concl_hash}, {@code ux_concl_scope_hash} and the
     * {@code collections} primary key, and far above this they still refuse the row. What V13 removes is
     * the content's contribution to that arithmetic.
     */
    @Test
    void longNamesAndALongConclusionStoreTogether() {
        PairKey named = seedPair(incompressibleLatin(600, 1), incompressibleLatin(600, 2));
        seedSession("s1");
        String content = hangul(FIRST_REFUSED_HANGUL);

        DedupOutcome outcome = conclusions.upsert(Drafts.explicit(named, "s1", content));

        assertThat(outcome.kind()).isEqualTo(DedupOutcome.Kind.INSERTED);
        assertThat(conclusions.find(WORKSPACE, outcome.conclusionId()).orElseThrow().content()).isEqualTo(content);
    }

    /** Stage 1 is a hash lookup and length was never its problem; it still answers first at 900. */
    @Test
    void dedupStillMatchesOnHashAtLength() {
        String content = hangul(FIRST_REFUSED_HANGUL);
        DedupOutcome first = conclusions.upsert(Drafts.explicit(pair(), "s1", content));

        DedupOutcome second = conclusions.upsert(Drafts.explicit(pair(), "s1", content));

        assertThat(second.kind()).isEqualTo(DedupOutcome.Kind.REINFORCED);
        assertThat(second.stage()).isEqualTo(1);
        assertThat(second.conclusionId()).isEqualTo(first.conclusionId());
    }

    /**
     * Stage 2 at length: the long-content twin of
     * {@code DedupTest.stageTwoAbsorbsRowsHashedUnderAnOlderRule}.
     *
     * <p>This is the stage that reads the index V13 rewrote, so it is the one a rewrite could break
     * without breaking anything else. A row is stored with a hash that disagrees with its own
     * normalised text — the state a change to {@code Normalizer} leaves behind — and the equality
     * beside the hash in {@code ConclusionRepository.NORM_LOOKUP} is what has to find it.
     */
    @Test
    void dedupStillMatchesOnNormalisedTextAtLength() {
        String content = hangul(FIRST_REFUSED_HANGUL);
        DedupOutcome stored = conclusions.upsert(
                draft(pair(), "s1", ConclusionLevel.EXPLICIT, content, Drafts.analyze(content), "0".repeat(64)));

        DedupOutcome current = conclusions.upsert(Drafts.explicit(pair(), "s1", content));

        assertThat(current.kind()).isEqualTo(DedupOutcome.Kind.REINFORCED);
        assertThat(current.stage()).isEqualTo(2);
        assertThat(current.conclusionId()).isEqualTo(stored.conclusionId());
    }

    /**
     * The dreamer's shape at length, which is a different branch of the dedup scope.
     *
     * <p>{@code dedupScopeSql} drops the {@code session_name} predicate for anything that is not
     * explicit — a deduction spans the pair's whole history — so a deductive conclusion reaches
     * {@link ConclusionRepository#NORM_LOOKUP} inside a different query than an explicit one does. The
     * dreamer is also the other writer that reaches {@code ConclusionWriter} with no DTO in the way,
     * which is half of why a cap on {@code CreateConclusion.content} could never have closed this.
     */
    @Test
    void aLongDeductiveConclusionStoresAndStillReachesStageTwo() {
        String content = hangul(FIRST_REFUSED_HANGUL);
        DedupOutcome stored = conclusions.upsert(
                draft(pair(), null, ConclusionLevel.DEDUCTIVE, content, Drafts.analyze(content), "0".repeat(64)));

        DedupOutcome current = conclusions
                .upsert(Drafts.of(pair(), null, content, ConclusionLevel.DEDUCTIVE, List.of()));

        assertThat(stored.kind()).isEqualTo(DedupOutcome.Kind.INSERTED);
        assertThat(conclusions.find(WORKSPACE, stored.conclusionId()).orElseThrow().content()).isEqualTo(content);
        assertThat(current.kind()).isEqualTo(DedupOutcome.Kind.REINFORCED);
        assertThat(current.stage()).isEqualTo(2);
        assertThat(current.conclusionId()).isEqualTo(stored.conclusionId());
    }

    /**
     * A draft whose analyzed form and hash are supplied rather than derived, so a test can put the row
     * into a state the write pipeline would not produce.
     */
    private static ConclusionDraft draft(PairKey pair, String session, ConclusionLevel level, String content,
            String analyzed, String hash) {
        return ConclusionDraft.builder().pair(pair).sessionName(session).content(content).contentNorm(content)
                .contentAnalyzed(analyzed).contentHash(hash).level(level).entityNames(List.of())
                .embedding(Drafts.embed(content)).actor(Actor.DERIVER).promptVersion("test-prompt-1").build();
    }

    /** {@code n} Hangul syllables, three UTF-8 bytes each, by the rule {@code SchemaPromiseTest} uses. */
    private static String hangul(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) (0xAC00 + (i * 37 % 11172)));
        }
        return sb.toString();
    }

    /**
     * {@code n} one-byte characters with nothing in them for pglz to fold: random hex from a fixed
     * seed, which is the shape the boundary was measured on (concatenated {@code md5(random()::text)})
     * and is reproducible run to run.
     */
    private static String incompressibleLatin(int n) {
        return incompressibleLatin(n, 0);
    }

    private static String incompressibleLatin(int n, long seed) {
        Random random = new Random(20260906L + n + seed);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append("0123456789abcdef".charAt(random.nextInt(16)));
        }
        return sb.toString();
    }
}
