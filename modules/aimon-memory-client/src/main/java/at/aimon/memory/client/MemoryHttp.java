package at.aimon.memory.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The transport. One place where a request becomes bytes and a response becomes a tree or an exception.
 *
 * <p>
 * The JDK client rather than a dependency: this module is on an aimon-core application's classpath, and the five
 * tiers need a POST with a bearer token and a JSON body. Anything larger would be a version of Netty or OkHttp that
 * a consumer now has to reconcile with its own.
 */
final class MemoryHttp {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final RemoteMemoryOptions options;
    private final String base;

    MemoryHttp(RemoteMemoryOptions options) {
        this.options = options;
        this.base = trimTrailingSlash(options.getBaseUri().toString());
        this.http = HttpClient.newBuilder().connectTimeout(options.getTimeout()).build();
    }

    static ObjectNode object() {
        return JSON.createObjectNode();
    }

    /**
     * Builds a request path out of segments, escaping each one.
     *
     * <p>
     * Only the query string used to be escaped, and that asymmetry was the bug. Workspace, peer and session names are
     * caller data — {@code Segments.required} checks that they are not blank and nothing else — and they were
     * concatenated into the path verbatim. A name containing a space made {@code URI.create} throw
     * {@code IllegalArgumentException}, which is not a {@link RemoteMemoryException} and so walked straight past the
     * "unreachable versus absent" distinction {@link #send} exists to preserve, reaching an aimon-core caller as
     * something its contract never mentions. A name containing {@code /}, {@code ..}, {@code ?} or {@code #} was
     * worse: it addressed a different endpoint, quietly and successfully.
     *
     * <p>
     * {@code URLEncoder} is form encoding, so it needs two corrections to be path encoding: {@code +} means a literal
     * plus inside a path and has to be written {@code %20}, and a segment that is exactly {@code .} or {@code ..}
     * comes through untouched and would still be resolved as a traversal.
     */
    static String path(String... segments) {
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            sb.append('/').append(encodeSegment(segment));
        }
        return sb.toString();
    }

    private static String encodeSegment(String segment) {
        String raw = segment == null ? "" : segment;
        if (".".equals(raw) || "..".equals(raw)) {
            return raw.replace(".", "%2E");
        }
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }

    JsonNode get(String path, Map<String, String> query) {
        return send(HttpRequest.newBuilder(URI.create(base + path + queryString(query))).GET(), options.getTimeout());
    }

    JsonNode post(String path, ObjectNode body, Duration timeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        return send(request, timeout);
    }

    /**
     * Sends the request, and turns anything that is not a 2xx into a {@link RemoteMemoryException}.
     *
     * <p>
     * A 404 comes back as {@code null} rather than as an exception. The tiers that can legitimately ask about
     * something absent — a peer with no conclusions yet — need "there is nothing" to be an answer; a caller deciding
     * whether to degrade has to be able to tell that from "the service is unreachable", and an exception for both
     * takes that distinction away. Every tier that receives null here says so in its own comment, because a null
     * return is only defensible where the caller has a name for it.
     */
    private JsonNode send(HttpRequest.Builder builder, Duration timeout) {
        HttpRequest request = builder.header("Authorization", "Bearer " + options.getToken().get())
                .header("Accept", "application/json").timeout(timeout).build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RemoteMemoryException("could not reach " + request.uri(), e);
        } catch (InterruptedException e) {
            // Restore the flag before leaving: the caller may be a task whose cancellation this is.
            Thread.currentThread().interrupt();
            throw new RemoteMemoryException("interrupted while calling " + request.uri(), e);
        }

        int status = response.statusCode();
        if (status == 404) {
            return null;
        }
        if (status < 200 || status >= 300) {
            throw failure(request.uri(), status, response.body());
        }
        if (response.body() == null || response.body().isBlank()) {
            return JSON.nullNode();
        }
        try {
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new RemoteMemoryException(request.uri() + " answered " + status + " with a body that is not JSON", e);
        }
    }

    /**
     * Builds the exception from the service's own error body when there is one.
     *
     * <p>
     * The API answers failures as {@code {"code": "...", "message": "..."}} with a code that is stable across
     * releases. Preserving both is what lets an operator tell {@code llm_not_configured} — a deployment that needs a
     * key — from {@code bad_scope}, which is this adapter or its caller getting the pair wrong.
     */
    private static RemoteMemoryException failure(URI uri, int status, String body) {
        String code = null;
        String message = null;
        if (body != null && !body.isBlank()) {
            try {
                JsonNode error = JSON.readTree(body);
                code = error.path("code").asText(null);
                message = error.path("message").asText(null);
            } catch (IOException ignored) {
                // Not the documented error shape. The status and the raw body are still worth reporting.
                message = body;
            }
        }
        String detail = message == null || message.isBlank() ? "no message" : message;
        return new RemoteMemoryException(uri + " answered " + status + ": " + detail, status, code);
    }

    private static String queryString(Map<String, String> query) {
        if (query.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (sb.length() > 1) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String trimTrailingSlash(String uri) {
        return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }
}
