package dev.dyad.store.repo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dyad.core.key.PairKey;
import dev.dyad.core.model.ConclusionLevel;
import dev.dyad.core.model.DedupOutcome;
import dev.dyad.core.model.EventType;
import dev.dyad.store.Drafts;
import dev.dyad.store.StoreTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The three-stage write path, one test per branch, plus the boundaries between them. */
class DedupTest extends StoreTestBase {

    /**
     * Thirty distinct words. The cosine threshold is 0.05, which one word out of six does not clear —
     * near-duplicate detection is for rephrasings of the same long statement, not for short sentences
     * that happen to share a subject.
     */
    private static final List<String> WORDS =
            List.of("alice", "works", "at", "the", "central", "bank", "in", "seoul", "gangnam", "district",
                    "near", "han", "river", "every", "weekday", "morning", "commuting", "by", "subway", "line",
                    "two", "from", "her", "apartment", "which", "she", "bought", "last", "year", "together");

    private static String sentence(int words) {
        return String.join(" ", WORDS.subList(0, words));
    }

    private PairKey pair;

    private PairKey pair() {
        if (pair == null) {
            pair = seedPair("alice", "alice");
            seedSession("s1");
        }
        return pair;
    }

    @Test
    void stageOneMatchesOnHashAndReinforces() {
        DedupOutcome first = conclusions.upsert(Drafts.explicit(pair(), "s1", "alice works at a bank"));
        DedupOutcome second = conclusions.upsert(Drafts.explicit(pair(), "s1", "alice works at a bank"));

        assertThat(first.kind()).isEqualTo(DedupOutcome.Kind.INSERTED);
        assertThat(second.kind()).isEqualTo(DedupOutcome.Kind.REINFORCED);
        assertThat(second.stage()).isEqualTo(1);
        assertThat(second.conclusionId()).isEqualTo(first.conclusionId());

        var stored = conclusions.find(WORKSPACE, first.conclusionId()).orElseThrow();
        assertThat(stored.timesDerived()).isEqualTo(2);
        assertThat(events.history(WORKSPACE, first.conclusionId(), 10))
                .extracting(e -> e.event())
                .containsExactly(EventType.REINFORCE, EventType.ADD);
    }

    /** Casing and padding normalise away, so the hash already matches and stage 1 catches them. */
    @Test
    void stageOneAbsorbsCasingAndWhitespace() {
        DedupOutcome first = conclusions.upsert(Drafts.explicit(pair(), "s1", "alice works at a bank"));
        DedupOutcome second = conclusions.upsert(Drafts.explicit(pair(), "s1", "  Alice Works At A Bank  "));

        assertThat(second.kind()).isEqualTo(DedupOutcome.Kind.REINFORCED);
        assertThat(second.stage()).isEqualTo(1);
        assertThat(second.conclusionId()).isEqualTo(first.conclusionId());
    }

    /**
     * Stage 2 is not redundant with stage 1, but the case it catches is narrow: a stored row whose
     * hash was computed under an older normalisation rule. The hash then disagrees while the
     * normalised text agrees, and without this stage the fact would be stored twice forever.
     *
     * <p>Simulated here by writing a row with a stale hash, which is exactly the state a change to
     * {@link dev.dyad.text.Normalizer} would leave behind.
     */
    @Test
    void stageTwoAbsorbsRowsHashedUnderAnOlderRule() {
        var legacy =
                dev.dyad.core.model.ConclusionDraft.builder()
                        .pair(pair())
                        .sessionName("s1")
                        .content("alice works at a bank")
                        .contentNorm("alice works at a bank")
                        .contentAnalyzed(Drafts.analyze("alice works at a bank"))
                        .contentHash("0".repeat(64))
                        .level(ConclusionLevel.EXPLICIT)
                        .embedding(Drafts.embed("alice works at a bank"))
                        .actor(dev.dyad.core.model.Actor.DERIVER)
                        .build();
        DedupOutcome stored = conclusions.upsert(legacy);

        DedupOutcome current = conclusions.upsert(Drafts.explicit(pair(), "s1", "alice works at a bank"));

        assertThat(current.kind()).isEqualTo(DedupOutcome.Kind.REINFORCED);
        assertThat(current.stage()).isEqualTo(2);
        assertThat(current.conclusionId()).isEqualTo(stored.conclusionId());
    }

    /**
     * Stage 3, replace branch: the newcomer carries strictly more information, so it wins and inherits
     * the reinforcement count. Losing the count would reset the {@code reinf} signal on every rewording.
     */
    @Test
    void stageThreeReplacesWhenTheNewPhrasingCarriesMore() {
        DedupOutcome first = conclusions.upsert(Drafts.explicit(pair(), "s1", sentence(30)));
        conclusions.upsert(Drafts.explicit(pair(), "s1", sentence(30)));

        DedupOutcome replacement =
                conclusions.upsert(Drafts.explicit(pair(), "s1", sentence(30) + " punctually"));

        assertThat(replacement.kind()).isEqualTo(DedupOutcome.Kind.REPLACED);
        assertThat(replacement.replacedId()).isEqualTo(first.conclusionId());
        assertThat(conclusions.find(WORKSPACE, first.conclusionId()).orElseThrow().isDeleted()).isTrue();

        var survivor = conclusions.find(WORKSPACE, replacement.conclusionId()).orElseThrow();
        assertThat(survivor.timesDerived()).isEqualTo(3);
        assertThat(events.history(WORKSPACE, replacement.conclusionId(), 10))
                .anySatisfy(e -> assertThat(e.event()).isEqualTo(EventType.REPLACE));
    }

    /** Stage 3, reinforce branch: a shorter restatement of the same fact loses. */
    @Test
    void stageThreeReinforcesWhenTheNewPhrasingCarriesLess() {
        DedupOutcome rich = conclusions.upsert(Drafts.explicit(pair(), "s1", sentence(30) + " punctually"));
        DedupOutcome poor = conclusions.upsert(Drafts.explicit(pair(), "s1", sentence(30)));

        assertThat(poor.kind()).isEqualTo(DedupOutcome.Kind.REINFORCED);
        assertThat(poor.conclusionId()).isEqualTo(rich.conclusionId());
        assertThat(conclusions.find(WORKSPACE, rich.conclusionId()).orElseThrow().isDeleted()).isFalse();
    }

    /**
     * Dedup is scoped to the pair. The same sentence observed by two peers is two memories, and
     * merging them would let one peer's deletion silently remove the other's.
     */
    @Test
    void dedupNeverCrossesThePair() {
        PairKey self = seedPair("alice", "alice");
        PairKey observed = seedPair("bob", "alice");
        seedSession("s1");

        DedupOutcome a = conclusions.upsert(Drafts.explicit(self, "s1", "alice works at a bank"));
        DedupOutcome b = conclusions.upsert(Drafts.explicit(observed, "s1", "alice works at a bank"));

        assertThat(b.kind()).isEqualTo(DedupOutcome.Kind.INSERTED);
        assertThat(b.conclusionId()).isNotEqualTo(a.conclusionId());
    }

    /** The same sentence in two sessions is two facts with two provenances. */
    @Test
    void explicitDedupNeverCrossesTheSession() {
        seedSession("s1");
        seedSession("s2");
        DedupOutcome a = conclusions.upsert(Drafts.explicit(pair(), "s1", "alice works at a bank"));
        DedupOutcome b = conclusions.upsert(Drafts.explicit(pair(), "s2", "alice works at a bank"));

        assertThat(b.kind()).isEqualTo(DedupOutcome.Kind.INSERTED);
        assertThat(b.conclusionId()).isNotEqualTo(a.conclusionId());
    }

    @Test
    void dedupNeverCrossesTheLevel() {
        conclusions.upsert(Drafts.explicit(pair(), "s1", "alice works at a bank"));
        DedupOutcome deduced =
                conclusions.upsert(
                        Drafts.of(pair(), null, "alice works at a bank", ConclusionLevel.DEDUCTIVE, List.of()));

        assertThat(deduced.kind()).isEqualTo(DedupOutcome.Kind.INSERTED);
    }

    /** A genuinely different fact must survive all three stages. */
    @Test
    void unrelatedContentIsInserted() {
        conclusions.upsert(Drafts.explicit(pair(), "s1", "alice works at a bank"));
        DedupOutcome other = conclusions.upsert(Drafts.explicit(pair(), "s1", "bob prefers tea over coffee"));
        assertThat(other.kind()).isEqualTo(DedupOutcome.Kind.INSERTED);
    }

    /**
     * Two writers, same fact, same instant.
     *
     * <p>The queue serialises work units for one pair, but that is not the only write path: direct
     * injection through the API, {@code ?wait=derive}, and the dreamer all reach {@code upsert}
     * independently. Stage 1 is a read followed by a write, so without a uniqueness guarantee
     * underneath, both writers see nothing and both insert — and the store ends up holding the same
     * sentence twice, permanently, with the reinforcement count split between the copies.
     */
    @Test
    void concurrentIdenticalWritesConvergeOnOneRow() throws Exception {
        PairKey target = pair();
        int writers = 12;

        try (java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Callable<DedupOutcome>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < writers; i++) {
                tasks.add(() -> conclusions.upsert(Drafts.explicit(target, "s1", "alice works at a bank")));
            }
            for (var future : pool.invokeAll(tasks)) {
                future.get();
            }
        }

        var stored = conclusions.list(target, dev.dyad.core.filter.Filter.ALL, 0, 50).items();
        assertThat(stored).as("one fact, one row").hasSize(1);
        // Nothing may be lost either: every writer's contribution has to land on the surviving row.
        assertThat(stored.get(0).timesDerived()).isEqualTo(writers);
    }

    @Test
    void everyWritePathLeavesAnEvent() {
        DedupOutcome inserted = conclusions.upsert(Drafts.explicit(pair(), "s1", "alice works at a bank"));
        conclusions.upsert(Drafts.explicit(pair(), "s1", "alice works at a bank"));
        conclusions.softDelete(
                WORKSPACE, inserted.conclusionId(), dev.dyad.core.model.Actor.API,
                java.util.Map.of(), EventType.DELETE);
        conclusions.restore(WORKSPACE, inserted.conclusionId(), dev.dyad.core.model.Actor.API);

        assertThat(events.history(WORKSPACE, inserted.conclusionId(), 10))
                .extracting(e -> e.event())
                .containsExactly(EventType.RESTORE, EventType.DELETE, EventType.REINFORCE, EventType.ADD);
    }
}
