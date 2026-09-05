package at.aimon.memory.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/**
 * The live API description is off unless a deployment asks for it.
 *
 * <p>The auth interceptor covers {@code /v1/**} and nothing else, so a springdoc endpoint left on
 * answers anything that can reach the port. The same reasoning moved actuator to its own port; here
 * the cheaper answer is that the document a consumer should read is committed to the repository, so
 * the running service does not have to serve it at all.
 *
 * <p>This class exists because that default is one line of YAML, and a default nobody tests is a
 * default that comes back on.
 */
class OpenApiExposureTest extends ApiTestBase {

    @Test
    void theDescriptionIsNotServedUnlessEnabled() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isNotFound());
    }

    /**
     * And nothing arrives alongside it that the flag does not govern.
     *
     * <p>The first version of this depended on springdoc's {@code -ui} starter, which brings
     * {@code org.webjars:swagger-ui} with it. Boot's default {@code /webjars/**} mapping then served the
     * whole bundle — unversioned alias included — on the service port, with no token, whatever
     * {@code AIMON_MEMORY_OPENAPI} was set to. No route of this API leaked through it, but a flag
     * reading "off" over 179KB of assets that were on is the kind of thing this class is for.
     */
    @Test
    void noSwaggerUiBundleIsServedAtAll() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
        mvc.perform(get("/webjars/swagger-ui/index.html")).andExpect(status().isNotFound());
    }
}
