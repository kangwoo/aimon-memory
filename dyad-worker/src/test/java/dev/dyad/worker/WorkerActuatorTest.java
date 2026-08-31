package dev.dyad.worker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dyad.testkit.db.PostgresSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The worker has to actually serve its metrics.
 *
 * <p>It was configured with actuator endpoints and a management port while depending on the plain
 * Spring Boot starter, so there was no servlet container and nothing was listening. The application
 * started cleanly and logged nothing unusual — the only symptom was a connection refused from
 * whatever was scraping it, which is precisely the kind of thing nobody notices until an incident.
 *
 * <p>Its metrics are the ones that matter most: queue depth and queue age are how a stalled worker
 * becomes visible, and a stalled worker is otherwise silent by construction.
 */
// Spring Boot switches metrics export off inside tests by default. Without turning it back on the
// Prometheus registry is absent, the endpoint 404s, and this test would "prove" a problem that only
// exists in the test context — while the real gap it was written for stayed invisible.
@AutoConfigureObservability
@SpringBootTest(classes = WorkerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkerActuatorTest {

    @LocalServerPort private int port;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresSupport::username);
        registry.add("spring.datasource.password", PostgresSupport::password);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
        return response.body();
    }

    @Test
    void healthIsReachable() throws Exception {
        assertThat(get("/actuator/health")).contains("\"status\":\"UP\"");
    }

    @Test
    void theQueueGaugesTheDashboardReliesOnAreScrapeable() throws Exception {
        String metrics = get("/actuator/prometheus");
        assertThat(metrics)
                .as("panels in docs/dashboards/dyad-overview.json query these by name")
                .contains("dyad_queue_pending")
                .contains("dyad_queue_oldest_seconds");
        assertThat(metrics).contains("hikaricp_connections_pending");
    }
}
