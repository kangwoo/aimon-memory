package at.aimon.memory.api.security;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import at.aimon.memory.core.NotFoundException;

/**
 * Authenticates the bearer token and checks it against the route table.
 *
 * <p>Fail-closed throughout. An unregistered route is refused rather than allowed; a path variable
 * the token does not cover is refused rather than ignored. Both are the kind of thing that, allowed
 * by default, produces a cross-tenant read that nothing logs.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String PRINCIPAL_ATTRIBUTE = "aimon.memory.principal";

    private final JwtService jwt;
    private final RoutePolicy policy;

    public AuthInterceptor(JwtService jwt, RoutePolicy policy) {
        this.jwt = jwt;
        this.policy = policy;
    }

    /**
     * Authenticate, then locate the route, then check the scope — in that order.
     *
     * <p>Authentication first means an unauthenticated caller learns nothing about which paths exist:
     * every request without a valid token is a 401, whatever it was aimed at. Only once a caller is
     * known does the difference between "no such endpoint" and "not yours" become visible, and at
     * that point telling them apart is a courtesy rather than a disclosure.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        MemoryPrincipal principal = jwt.verify(bearerToken(request));
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);

        // Anything under /v1 that is not a controller method is not an endpoint. Falling through to
        // the policy check reported those as 403, which reads as "exists, but not for you" and is
        // both wrong and confusing. It also meant a static resource that happened to sit under /v1
        // would be served with no policy applied at all.
        if (!(handler instanceof HandlerMethod)) {
            throw new NotFoundException("endpoint", request.getMethod() + " " + request.getRequestURI());
        }

        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            throw new NotFoundException("endpoint", request.getMethod() + " " + request.getRequestURI());
        }
        RoutePolicy.Rule rule = policy.ruleFor(request.getMethod(), pattern);
        if (rule == null) {
            throw new ForbiddenException("route " + request.getMethod() + " " + pattern
                    + " has no entry in RoutePolicy; register it before it can be called");
        }

        if (!principal.scope().satisfies(rule.minimumScope())) {
            // A session token may still read a member-read route, but only if its own token says so.
            boolean memberReadAllowed = rule.memberRead() && principal.scope() == TokenScope.SESSION
                    && principal.allowMemberRead();
            if (!memberReadAllowed) {
                throw new ForbiddenException("this token is " + principal.scope().wire()
                        + "-scoped; the route requires " + rule.minimumScope().wire());
            }
        }
        checkPathScope(request, principal);
        return true;
    }

    @SuppressWarnings("unchecked")
    private void checkPathScope(HttpServletRequest request, MemoryPrincipal principal) {
        Map<String, String> variables = (Map<String, String>) request
                .getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables == null) {
            return;
        }
        String workspace = variables.get("workspace");
        if (workspace != null && !principal.canReachWorkspace(workspace)) {
            throw new ForbiddenException("token is not scoped to workspace " + workspace);
        }
        String peer = variables.get("peer");
        if (peer != null && !principal.canReachPeer(peer)) {
            throw new ForbiddenException("token is not scoped to peer " + peer);
        }
        String session = variables.get("session");
        if (session != null && !principal.canReachSession(session)) {
            throw new ForbiddenException("token is not scoped to session " + session);
        }
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new UnauthorizedException("missing bearer token");
        }
        return header.substring("Bearer ".length()).trim();
    }
}
