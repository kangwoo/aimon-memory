package dev.dyad.worker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dyad.core.config.BatchSettings;
import dev.dyad.core.key.PairKey;
import dev.dyad.core.key.WorkUnitKey;
import dev.dyad.store.repo.QueueRepository;
import dev.dyad.store.repo.WorkspaceRepository;
import dev.dyad.testkit.db.PostgresSupport;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The worker process starts, wires every consumer, and polls.
 *
 * <p>Worth its own test because the two applications share a codebase but not a context: a bean the
 * API happens to provide is not automatically there for the worker, and that only shows up on start.
 */
@SpringBootTest(classes = WorkerApplication.class)
class WorkerApplicationTest {

    @Autowired private WorkerLoop loop;
    @Autowired private WorkerProperties properties;
    @Autowired private ReconcilerService reconciler;
    @Autowired private QueueRepository queue;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private dev.dyad.store.WorkspaceSettingsService settings;
    @Autowired private QueueMetrics queueMetrics;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meters;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresSupport::username);
        registry.add("spring.datasource.password", PostgresSupport::password);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @BeforeEach
    void reset() {
        PostgresSupport.dataSource();
        PostgresSupport.truncateAll();
    }

    @Test
    void theContextStartsWithEveryCollaboratorWired() {
        assertThat(loop).isNotNull();
        assertThat(reconciler).isNotNull();
        assertThat(properties.workerId()).isNotBlank();
        assertThat(properties.concurrency()).isPositive();
    }

    @Test
    void anEmptyQueuePollsWithoutClaimingAnything() {
        loop.start();
        assertThat(loop.pollOnce()).isFalse();
    }

    /**
     * Batch gating is per workspace, so a tenant that configures a shorter idle flush actually gets
     * one. Gating everything by a global default would make that setting decorative.
     */
    @Test
    void eachWorkspaceIsGatedByItsOwnBatchSettings() {
        workspaces.getOrCreate("patient", Map.of(), Map.of("batch.idle_flush_seconds", 3600));
        workspaces.getOrCreate("eager", Map.of(), Map.of("batch.idle_flush_seconds", 0));
        settings.invalidate("patient");
        settings.invalidate("eager");

        queue.enqueue(
                new WorkUnitKey(
                        dev.dyad.core.key.TaskType.REPRESENTATION, "patient", "s1", "alice", "alice"),
                Map.of("message_id", 1), 1);
        queue.enqueue(
                new WorkUnitKey(dev.dyad.core.key.TaskType.REPRESENTATION, "eager", "s1", "alice", "alice"),
                Map.of("message_id", 2), 1);

        assertThat(queue.workspacesWithPendingWork(10)).containsExactly("eager", "patient");

        BatchSettings patient = settings.forWorkspace("patient").batch();
        BatchSettings eager = settings.forWorkspace("eager").batch();
        assertThat(patient.idleFlush()).isEqualTo(Duration.ofSeconds(3600));
        assertThat(eager.idleFlush()).isZero();

        assertThat(queue.ready("patient", patient, 10)).isEmpty();
        assertThat(queue.ready("eager", eager, 10)).hasSize(1);
    }

    @Test
    void theReconcilerRunsAgainstAnEmptyDatabaseWithoutComplaining() {
        var report = reconciler.run(Duration.ofDays(7));
        assertThat(report.total()).isZero();
    }

    /** The dashboard's two headline panels have to be backed by metrics that actually exist. */
    @Test
    void queueDepthAndAgeArePublishedAsGauges() {
        queueMetrics.sample();
        assertThat(meters.find("dyad.queue.pending").gauge()).isNotNull();
        assertThat(meters.find("dyad.queue.oldest.seconds").gauge()).isNotNull();
        assertThat(meters.get("dyad.queue.pending").gauge().value()).isZero();

        queue.enqueue(
                new WorkUnitKey(
                        dev.dyad.core.key.TaskType.REPRESENTATION, "metrics", "s1", "alice", "alice"),
                Map.of("message_id", 1), 10);
        queueMetrics.sample();

        assertThat(meters.get("dyad.queue.pending").gauge().value()).isEqualTo(1.0);
        // Age matters more than depth: a deep queue that drains is busy, a shallow one that ages is stalled.
        assertThat(meters.get("dyad.queue.oldest.seconds").gauge().value()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void claimsAreScopedToThisWorkerId() {
        String key = WorkUnitKey.representation("ws", "s1", new PairKey("ws", "a", "b")).encode();
        assertThat(queue.claim(key, properties.workerId(), Duration.ofMinutes(1))).isTrue();
        assertThat(queue.claim(key, "someone-else", Duration.ofMinutes(1))).isFalse();
    }
}
