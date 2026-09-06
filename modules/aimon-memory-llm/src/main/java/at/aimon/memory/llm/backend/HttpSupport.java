package at.aimon.memory.llm.backend;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import at.aimon.memory.llm.Json;
import at.aimon.memory.llm.LlmException;

/** Shared HTTP plumbing for the provider backends. */
final class HttpSupport {

    private static final Logger log = LoggerFactory.getLogger(HttpSupport.class);

    private HttpSupport() {
    }

    static HttpRequest.Builder request(String url, Map<String, String> headers, Duration timeout, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return builder;
    }

    /**
     * The provider's own words go to the log; the caller gets the status code.
     *
     * <p>{@code LlmException} is a {@code MemoryException}, so whatever is put in this message is what
     * {@code ApiExceptionHandler} writes into the 500 body. The provider's error body is not the
     * caller's text: an OpenAI 401 quotes the configured API key back with only its middle masked, a
     * gateway in front of the provider answers with HTML naming internal hosts, and a rejection for an
     * over-long prompt can echo the prompt — which this build assembles out of the workspace's stored
     * conclusions and messages. None of that is something the caller of this request sent, so none of
     * it belongs in the answer to it.
     *
     * <p>WARN rather than ERROR, and at the throw site rather than only at the handler: most calls
     * through here are inside {@code FallbackChatBackend}'s plan, so a failure logged at ERROR would be
     * one the next attempt quietly recovers from. The handler still logs ERROR for the failures that
     * actually reach a response.
     */
    static JsonNode send(HttpClient http, HttpRequest request, String provider) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("{} returned HTTP {}: {}", provider, response.statusCode(), response.body());
                throw new LlmException(errorCode(response.statusCode()),
                        provider + " returned HTTP " + response.statusCode());
            }
            return Json.read(response.body());
        } catch (IOException e) {
            log.warn("{} call failed", provider, e);
            throw new LlmException("llm_transport", provider + " call failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("llm_interrupted", provider + " call interrupted");
        }
    }

    static Stream<String> sendLines(HttpClient http, HttpRequest request, String provider) {
        try {
            HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() / 100 != 2) {
                response.body().close();
                throw new LlmException(errorCode(response.statusCode()),
                        provider + " stream returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            log.warn("{} stream failed", provider, e);
            throw new LlmException("llm_transport", provider + " stream failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("llm_interrupted", provider + " stream interrupted");
        }
    }

    /**
     * Retryability is encoded in the exception code rather than decided at the call site, so the
     * fallback chain can tell "this provider is struggling" from "this request is malformed" without
     * re-parsing HTTP status codes it never saw.
     */
    private static String errorCode(int status) {
        if (status == 429 || status >= 500 || status == 408) {
            return "llm_retryable";
        }
        if (status == 401 || status == 403) {
            return "llm_auth";
        }
        return "llm_rejected";
    }
}
