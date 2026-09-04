package at.aimon.memory.api.security;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * The authorisation table: one explicit entry per route.
 *
 * <p>Deliberately a table and not annotations. A route with no entry is refused, so adding an
 * endpoint without deciding who may call it fails immediately — and an ArchUnit-style test walks the
 * handler mappings and fails the build if any route is missing here. The failure mode this prevents
 * is the ordinary one: a new endpoint ships with whatever the framework's default was.
 *
 * <p>{@code memberRead} marks the handful of read routes a session-scoped token may call, and only
 * when its own token carries {@code allow_member_read}. Two conditions rather than one, because the
 * narrow token is the one that ends up in a browser.
 */
@Component
public class RoutePolicy {

    /** @param memberRead whether a session token carrying allow_member_read may call this route */
    public record Rule(TokenScope minimumScope, boolean memberRead) {
    }

    private record Route(String method, String pattern) {
    }

    private final Map<Route, Rule> rules = new LinkedHashMap<>();

    public RoutePolicy() {
        // Minting is delegation, and delegation is the reason the scopes nest: a service holding a
        // workspace token mints a session token for one conversation and hands it to a client, which
        // cannot widen it back. Admin-only minting forced the opposite — an admin key in every service
        // that needed to hand out a narrow token. TokenController enforces that what comes out is never
        // wider than the token that asked for it; peer and session tokens cannot reach here at all,
        // since neither satisfies a workspace-scoped route.
        register("POST", "/v1/tokens", TokenScope.WORKSPACE, false);
        register("GET", "/v1/workspaces", TokenScope.ADMIN, false);

        register("POST", "/v1/workspaces/{workspace}", TokenScope.ADMIN, false);
        register("GET", "/v1/workspaces/{workspace}", TokenScope.WORKSPACE, false);
        register("PUT", "/v1/workspaces/{workspace}/configuration", TokenScope.WORKSPACE, false);

        register("POST", "/v1/workspaces/{workspace}/peers/{peer}", TokenScope.WORKSPACE, false);
        register("GET", "/v1/workspaces/{workspace}/peers", TokenScope.WORKSPACE, false);
        register("GET", "/v1/workspaces/{workspace}/peers/{peer}", TokenScope.PEER, false);
        register("PUT", "/v1/workspaces/{workspace}/peers/{peer}/configuration", TokenScope.PEER, false);

        register("POST", "/v1/workspaces/{workspace}/sessions/{session}", TokenScope.WORKSPACE, false);
        register("GET", "/v1/workspaces/{workspace}/sessions", TokenScope.WORKSPACE, false);
        register("GET", "/v1/workspaces/{workspace}/sessions/{session}", TokenScope.PEER, true);
        register("GET", "/v1/workspaces/{workspace}/sessions/{session}/peers", TokenScope.PEER, true);
        register("POST", "/v1/workspaces/{workspace}/sessions/{session}/peers", TokenScope.PEER, false);
        register("PUT", "/v1/workspaces/{workspace}/sessions/{session}/peers", TokenScope.PEER, false);
        register("DELETE", "/v1/workspaces/{workspace}/sessions/{session}/peers/{peer}", TokenScope.PEER, false);

        // Writing into its own session is what a session token is for, so no member-read opt-in.
        register("POST", "/v1/workspaces/{workspace}/sessions/{session}/messages", TokenScope.SESSION, false);
        register("GET", "/v1/workspaces/{workspace}/sessions/{session}/messages", TokenScope.PEER, true);
        register("POST", "/v1/workspaces/{workspace}/sessions/{session}/messages/search", TokenScope.PEER, true);
        register("GET", "/v1/workspaces/{workspace}/sessions/{session}/context", TokenScope.PEER, true);

        register("POST", "/v1/workspaces/{workspace}/recall", TokenScope.PEER, false);
        register("GET", "/v1/workspaces/{workspace}/recall/provenance", TokenScope.PEER, false);

        register("GET", "/v1/workspaces/{workspace}/conclusions", TokenScope.PEER, false);
        register("POST", "/v1/workspaces/{workspace}/conclusions", TokenScope.PEER, false);
        register("DELETE", "/v1/workspaces/{workspace}/conclusions/{id}", TokenScope.PEER, false);
        register("GET", "/v1/workspaces/{workspace}/conclusions/{id}/events", TokenScope.PEER, false);
        register("GET", "/v1/workspaces/{workspace}/conclusions/{id}/chain", TokenScope.PEER, false);

        // Chat names its own (observer, observed) pair in the body, so it reaches a whole pair's memory
        // rather than one session. A session token has no peer identity to check that against, which is
        // why this requires a peer token even though the questions are asked inside a session.
        register("POST", "/v1/workspaces/{workspace}/chat", TokenScope.PEER, false);
        register("POST", "/v1/workspaces/{workspace}/chat/stream", TokenScope.PEER, false);

        register("POST", "/v1/workspaces/{workspace}/dreams", TokenScope.WORKSPACE, false);
        register("GET", "/v1/workspaces/{workspace}/dreams", TokenScope.WORKSPACE, false);
        register("GET", "/v1/workspaces/{workspace}/peer-card", TokenScope.PEER, false);
        register("POST", "/v1/workspaces/{workspace}/peer-card/refresh", TokenScope.PEER, false);
    }

    private void register(String method, String pattern, TokenScope minimumScope, boolean memberRead) {
        rules.put(new Route(method, pattern), new Rule(minimumScope, memberRead));
    }

    public Rule ruleFor(String method, String pattern) {
        return rules.get(new Route(method, pattern));
    }

    public boolean isRegistered(String method, String pattern) {
        return rules.containsKey(new Route(method, pattern));
    }

    public int size() {
        return rules.size();
    }
}
