package at.aimon.memory.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

import at.aimon.memory.engine.MemoryConfiguration;

/**
 * The worker process.
 *
 * <p>Same codebase as the API, different entry point. Two processes rather than one because their
 * failure modes and their scaling curves have nothing in common: the API is latency-bound and should
 * be restarted freely, the worker is provider-bound and holds claims that a restart has to release.
 */
@SpringBootApplication
@Import(MemoryConfiguration.class)
@EnableConfigurationProperties(WorkerProperties.class)
// Not a utility class with an oversight, a Spring Boot entry point: @SpringBootApplication is a
// @Configuration, and Spring subclasses those with CGLIB, which cannot call a private constructor.
@SuppressWarnings("checkstyle:HideUtilityClassConstructor")
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
