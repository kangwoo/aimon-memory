package dev.dyad.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param workerId identifies claims; defaults to hostname plus a random suffix so two workers on one
 *     host do not release each other's claims
 * @param claimTtl how long a claim survives without being extended — the upper bound on how long a
 *     work unit is stuck after a worker dies
 * @param maxAttempts failures before a batch is quarantined; a poison message on a serialised key
 *     blocks everything behind it, so it has to be given up on eventually
 */
@ConfigurationProperties(prefix = "dyad.worker")
public record WorkerProperties(
        String workerId,
        int concurrency,
        int unitsPerPoll,
        int itemsPerUnit,
        Duration claimTtl,
        Duration minPollInterval,
        Duration maxPollInterval,
        int maxAttempts,
        Duration reconcilerInterval,
        Duration processedRetention) {

    public WorkerProperties {
        if (workerId == null || workerId.isBlank()) {
            workerId = defaultWorkerId();
        }
        if (concurrency <= 0) {
            concurrency = 4;
        }
        if (unitsPerPoll <= 0) {
            unitsPerPoll = 8;
        }
        if (itemsPerUnit <= 0) {
            itemsPerUnit = 100;
        }
        if (claimTtl == null) {
            claimTtl = Duration.ofMinutes(5);
        }
        if (minPollInterval == null) {
            minPollInterval = Duration.ofSeconds(1);
        }
        if (maxPollInterval == null) {
            maxPollInterval = Duration.ofSeconds(30);
        }
        if (maxAttempts <= 0) {
            maxAttempts = 5;
        }
        if (reconcilerInterval == null) {
            reconcilerInterval = Duration.ofMinutes(5);
        }
        if (processedRetention == null) {
            processedRetention = Duration.ofDays(7);
        }
    }

    private static String defaultWorkerId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            host = "worker";
        }
        return host + "-" + Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt());
    }
}
