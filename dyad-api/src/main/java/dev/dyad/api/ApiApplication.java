package dev.dyad.api;

import dev.dyad.api.security.JwtProperties;
import dev.dyad.memory.DyadConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * The HTTP process.
 *
 * <p>Spring MVC on virtual threads rather than a reactive stack. Almost every handler here is a few
 * blocking JDBC calls, which reads as ordinary sequential code and stack-traces like it; the one
 * place that waits a long time is the dialectic, and a virtual thread parked on a socket costs
 * nothing. Reactive would buy the same concurrency at the price of every stack trace in the system.
 */
@SpringBootApplication
@Import(DyadConfiguration.class)
@EnableConfigurationProperties(JwtProperties.class)
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
