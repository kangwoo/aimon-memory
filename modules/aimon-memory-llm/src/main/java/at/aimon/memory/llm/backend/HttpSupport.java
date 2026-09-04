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

import com.fasterxml.jackson.databind.JsonNode;

import at.aimon.memory.llm.Json;
import at.aimon.memory.llm.LlmException;

/** Shared HTTP plumbing for the provider backends. */
final class HttpSupport {

    private HttpSupport() {
    }

    static HttpRequest.Builder request(String url, Map<String, String> headers, Duration timeout, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return builder;
    }

    static JsonNode send(HttpClient http, HttpRequest request, String provider) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new LlmException(errorCode(response.statusCode()),
                        provider + " returned HTTP " + response.statusCode() + ": " + preview(response.body()));
            }
            return Json.read(response.body());
        } catch (IOException e) {
            throw new LlmException("llm_transport", provider + " call failed: " + e.getMessage());
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
            throw new LlmException("llm_transport", provider + " stream failed: " + e.getMessage());
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

    static String preview(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 500 ? body : body.substring(0, 500) + "…";
    }
}
