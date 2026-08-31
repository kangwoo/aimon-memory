package dev.dyad.llm.replay;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dyad.core.spi.llm.ResponseFormat;
import dev.dyad.llm.Json;
import dev.dyad.llm.LlmException;
import dev.dyad.llm.backend.ChatCall;
import dev.dyad.llm.backend.ChatTurn;
import dev.dyad.llm.backend.ToolResult;
import dev.dyad.llm.backend.ToolSpec;
import dev.dyad.llm.backend.ToolUse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Content-addressed fixture identity: SHA-256 over {@code (model, system, messages, tools,
 * response_format)}.
 *
 * <p>Serialisation is written out by hand in a fixed field order rather than reflected, because the
 * hash has to stay stable across refactors of the record types. If a field is added to {@link
 * ChatCall} and not added here, it will not participate in the key — that is a deliberate opt-in.
 *
 * <p>Temperature and max tokens are excluded: they change what a live provider returns, but a fixture
 * is a frozen answer, and keying on them would invalidate the whole corpus every time a budget moves.
 *
 * <p>Whether the call streams <em>is</em> in the key, because the two produce different recordings —
 * one response object, or a list of chunks — and nothing else distinguishes them: an identical
 * request through {@code chat} and through {@code stream} hashes the same. Sharing a key meant a
 * replayed stream found the chat fixture, read a null chunk list and returned an empty stream: an SSE
 * response that completed normally having emitted nothing, with no fixture miss to say so. Recording
 * was worse — each write rebuilds the file from scratch, so recording one erased the other.
 */
public final class FixtureKey {

    private FixtureKey() {}

    /** The key for a blocking call. */
    public static String of(ChatCall call) {
        return of(call, false);
    }

    public static String of(ChatCall call, boolean streaming) {
        return sha256(canonical(call, streaming));
    }

    public static String canonical(ChatCall call) {
        return canonical(call, false);
    }

    /** The exact bytes that get hashed. Written into the fixture so a mismatch is diffable. */
    public static String canonical(ChatCall call, boolean streaming) {
        ObjectNode root = Json.object();
        root.put("model", call.model());
        root.put("system", call.system());
        root.put("stream", streaming);

        ArrayNode turns = root.putArray("turns");
        for (ChatTurn turn : call.turns()) {
            turns.add(canonicalTurn(turn));
        }

        ArrayNode tools = root.putArray("tools");
        for (ToolSpec tool : call.tools()) {
            ObjectNode t = Json.object();
            t.put("name", tool.name());
            t.put("description", tool.description());
            t.put("parameters", tool.parametersSchema());
            tools.add(t);
        }

        ResponseFormat format = call.responseFormat();
        if (format == null) {
            root.putNull("response_format");
        } else {
            ObjectNode f = root.putObject("response_format");
            f.put("name", format.name());
            f.put("schema", format.jsonSchema());
            f.put("strict", format.strict());
        }
        return root.toString();
    }

    private static ObjectNode canonicalTurn(ChatTurn turn) {
        ObjectNode node = Json.object();
        switch (turn) {
            case ChatTurn.UserText t -> {
                node.put("role", "user");
                node.put("text", t.text());
            }
            case ChatTurn.AssistantText t -> {
                node.put("role", "assistant");
                node.put("text", t.text());
            }
            case ChatTurn.AssistantToolUse t -> {
                node.put("role", "assistant_tool_use");
                node.put("text", t.text());
                ArrayNode uses = node.putArray("uses");
                for (ToolUse use : t.uses()) {
                    ObjectNode u = Json.object();
                    // The provider's call id is deliberately excluded: it is random per response, and
                    // including it would make every recorded loop unreplayable after the first turn.
                    u.put("name", use.name());
                    u.put("arguments", use.argumentsJson());
                    uses.add(u);
                }
            }
            case ChatTurn.ToolResults t -> {
                node.put("role", "tool_results");
                ArrayNode results = node.putArray("results");
                for (ToolResult result : t.results()) {
                    ObjectNode r = Json.object();
                    r.put("name", result.name());
                    r.put("content", result.content());
                    r.put("is_error", result.isError());
                    results.add(r);
                }
            }
        }
        return node;
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new LlmException("SHA-256 unavailable", e);
        }
    }
}
