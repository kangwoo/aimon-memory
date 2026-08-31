package dev.dyad.worker;

import dev.dyad.memory.DyadConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * The worker process.
 *
 * <p>Same codebase as the API, different entry point. Two processes rather than one because their
 * failure modes and their scaling curves have nothing in common: the API is latency-bound and should
 * be restarted freely, the worker is provider-bound and holds claims that a restart has to release.
 */
@SpringBootApplication
@Import(DyadConfiguration.class)
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
