package at.aimon.memory.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import at.aimon.memory.api.security.RoutePolicy;

/**
 * Every route must have an explicit entry in the authorisation table.
 *
 * <p>This is the gate the plan calls for by name, and it is the one test here that prevents a whole
 * class of mistake rather than a specific bug: adding an endpoint without deciding who may call it
 * fails the build, instead of shipping with whatever the framework's default happened to be.
 */
class RoutePolicyCoverageTest extends ApiTestBase {

    @Autowired
    private RoutePolicy policy;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    void everyApplicationRouteIsRegistered() {
        List<String> unregistered = new ArrayList<>();

        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            var patterns = info.getPathPatternsCondition();
            if (patterns == null) {
                continue;
            }
            for (var pattern : patterns.getPatterns()) {
                String path = pattern.getPatternString();
                if (!path.startsWith("/v1/")) {
                    continue;
                }
                for (var method : info.getMethodsCondition().getMethods()) {
                    if (!policy.isRegistered(method.name(), path)) {
                        unregistered.add(method.name() + " " + path);
                    }
                }
            }
        }

        assertThat(unregistered).as("routes missing from RoutePolicy; add an entry naming the scope that may call them")
                .isEmpty();
    }

    /** The reverse direction: a policy entry for a route that no longer exists is dead configuration. */
    @Test
    void thePolicyHasNoEntriesForRoutesThatDoNotExist() {
        List<String> live = new ArrayList<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            var patterns = info.getPathPatternsCondition();
            if (patterns == null) {
                continue;
            }
            for (var pattern : patterns.getPatterns()) {
                for (var method : info.getMethodsCondition().getMethods()) {
                    live.add(method.name() + " " + pattern.getPatternString());
                }
            }
        }
        assertThat(policy.size()).isEqualTo(live.stream().filter(r -> r.contains("/v1/")).distinct().count());
    }
}
