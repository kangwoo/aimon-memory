package dev.dyad.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dyad.testkit.db.PostgresSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Actuator answers on the management port and nowhere else.
 *
 * <p>The auth interceptor covers {@code /v1/**} only. Metrics served on the connector that answers
 * the API would therefore be readable by anything that can reach the service, with no token — and for
 * a service holding personal memory that is not a detail. The separation is the protection, so it is
 * worth a test that would notice it being undone.
 */
@AutoConfigureObservability
@SpringBootTest(
        classes = ApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class ApiActuatorTest {

    @LocalServerPort private int servicePort;
    @LocalManagementPort private int managementPort;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresSupport::username);
        registry.add("spring.datasource.password", PostgresSupport::password);
        registry.add("dyad.jwt.secret", () -> "a-test-secret-that-is-long-enough-for-hs256");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void metricsAreServedOnTheManagementPort() throws Exception {
        // Make a real request first: the HTTP timer the dashboard's latency panels read only exists
        // once something has been served, so scraping a freshly started process would prove nothing.
        get(servicePort, "/v1/workspaces");

        HttpResponse<String> response = get(managementPort, "/actuator/prometheus");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .as("panels in docs/dashboards/dyad-overview.json query these by name")
                .contains("http_server_requests_seconds")
                .contains("jvm_memory_used_bytes");
    }

    @Test
    void theServicePortServesNoActuatorEndpoints() throws Exception {
        assertThat(managementPort).isNotEqualTo(servicePort);
        assertThat(get(servicePort, "/actuator/prometheus").statusCode())
                .as("metrics must not be reachable on the connector that answers /v1")
                .isEqualTo(404);
        assertThat(get(servicePort, "/actuator/health").statusCode()).isEqualTo(404);
    }
}
