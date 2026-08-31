package dev.dyad.memory.summarize;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dyad.memory.MemoryTestBase;
import dev.dyad.store.repo.MessageRepository;
import dev.dyad.testkit.stub.StubLlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SummarizerTest extends MemoryTestBase {

    private void seedMessages(int count) {
        seedSession("s1");
        peers.getOrCreate(WORKSPACE, "alice", Map.of(), Map.of());
        long start = sessions.nextSequence(WORKSPACE, "s1", count);
        List<MessageRepository.NewMessage> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new MessageRepository.NewMessage("alice", "message " + i, 5, Map.of()));
        }
        messages.insertBatch(WORKSPACE, "s1", start, rows);
    }

    private SummarizerService summarizer(StubLlmClient stub) {
        return new SummarizerService(stub, sessions, messages, CLOCK);
    }

    @Test
    void nothingHappensBelowTheThreshold() {
        seedMessages(19);
        assertThat(summarizer(StubLlmClient.returning("{\"summary\":\"x\"}")).refresh(WORKSPACE, "s1"))
                .isEmpty();
    }

    @Test
    void theShortSummaryArrivesFirst() {
        seedMessages(20);
        var produced = summarizer(StubLlmClient.returning("{\"summary\":\"short one\"}"))
                .refresh(WORKSPACE, "s1");

        assertThat(produced).singleElement().satisfies(summary -> {
            assertThat(summary.kind()).isEqualTo(Summary.SHORT);
            assertThat(summary.coversThroughSeq()).isEqualTo(20);
            assertThat(summary.tokenCount()).isGreaterThan(0);
        });
    }

    @Test
    void bothSummariesAppearOnceTheLongThresholdIsCrossed() {
        seedMessages(60);
        var produced = summarizer(StubLlmClient.returning("{\"summary\":\"a summary\"}"))
                .refresh(WORKSPACE, "s1");
        assertThat(produced).extracting(s -> s.kind()).containsExactly(Summary.SHORT, Summary.LONG);
    }

    /**
     * Each regeneration subsumes the previous summary rather than summarising a summary. Chaining
     * loses information geometrically; subsuming keeps one lineage covering the whole session.
     */
    @Test
    void regenerationIsGivenThePreviousSummaryAndReplacesIt() {
        seedMessages(20);
        StubLlmClient stub = StubLlmClient.returning("{\"summary\":\"first pass\"}");
        summarizer(stub).refresh(WORKSPACE, "s1");
        assertThat(stub.lastRequest().messages().get(0).content()).doesNotContain("Summary so far");

        seedMessages(20);
        StubLlmClient second = StubLlmClient.returning("{\"summary\":\"second pass\"}");
        var produced = summarizer(second).refresh(WORKSPACE, "s1");

        assertThat(second.lastRequest().messages().get(0).content()).contains("Summary so far").contains("first pass");
        assertThat(produced.get(0).text()).isEqualTo("second pass");

        var session = sessions.find(WORKSPACE, "s1").orElseThrow();
        assertThat(summarizer(second).find(session, Summary.SHORT).orElseThrow().text())
                .isEqualTo("second pass");
    }

    @Test
    void summariesSurviveASerialisationRoundTrip() {
        Summary summary = new Summary(Summary.SHORT, "text", 42, 7, NOW);
        assertThat(Summary.fromMap(summary.toMap())).isEqualTo(summary);
    }
}
