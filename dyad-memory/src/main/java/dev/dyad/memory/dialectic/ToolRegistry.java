package dev.dyad.memory.dialectic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dyad.core.filter.Filter;
import dev.dyad.core.filter.FilterOp;
import dev.dyad.core.key.PairKey;
import dev.dyad.core.model.Conclusion;
import dev.dyad.core.model.Message;
import dev.dyad.core.model.ScoredConclusion;
import dev.dyad.core.spi.llm.ToolDef;
import dev.dyad.recall.ProvenanceService;
import dev.dyad.recall.RecallRequest;
import dev.dyad.recall.RecallService;
import dev.dyad.store.repo.ConclusionRepository;
import dev.dyad.store.repo.MessageRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The seven tools the dialectic can call.
 *
 * <p>The important one is {@code recall}: Tier 1 in full, not a bare vector search. A better first
 * result is what shortens the loop — the goal was never to replace the agent but to stop it spending
 * six iterations reconstructing what one good retrieval would have handed it.
 *
 * <p>Every tool is pair-scoped by construction. The pair is bound when the toolset is built, and no
 * tool takes an observer or observed argument, so there is no argument the model can produce that
 * reaches another pair's memory. The three message tools carry the observer down into the query for
 * the same reason: a chat request may legitimately omit the session, and the scope that falls back to
 * has to be "what this observer heard", not "the workspace".
 *
 * <p>Output is compact text rather than JSON. A model reads it either way, and text is roughly half
 * the tokens.
 */
@Service
public class ToolRegistry {

    public static final String RECALL = "recall";
    public static final String SEARCH_MESSAGES = "search_messages";
    public static final String GREP_MESSAGES = "grep_messages";
    public static final String MESSAGES_BY_DATE = "messages_by_date";
    public static final String SEARCH_TEMPORAL = "search_temporal";
    public static final String REASONING_CHAIN = "reasoning_chain";
    public static final String ENTITY_PROVENANCE = "entity_provenance";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final RecallService recall;
    private final ProvenanceService provenance;
    private final MessageRepository messages;
    private final ConclusionRepository conclusions;

    public ToolRegistry(
            RecallService recall,
            ProvenanceService provenance,
            MessageRepository messages,
            ConclusionRepository conclusions) {
        this.recall = recall;
        this.provenance = provenance;
        this.messages = messages;
        this.conclusions = conclusions;
    }

    /** Build the toolset for one question, bound to one pair and one session. */
    public List<ToolDef> toolsFor(PairKey pair, String sessionName, List<String> names) {
        List<ToolDef> tools = new ArrayList<>(names.size());
        for (String name : names) {
            tools.add(
                    switch (name) {
                        case RECALL -> recallTool(pair);
                        case SEARCH_MESSAGES -> searchMessagesTool(pair, sessionName);
                        case GREP_MESSAGES -> grepMessagesTool(pair, sessionName);
                        case MESSAGES_BY_DATE -> messagesByDateTool(pair, sessionName);
                        case SEARCH_TEMPORAL -> searchTemporalTool(pair);
                        case REASONING_CHAIN -> reasoningChainTool(pair);
                        case ENTITY_PROVENANCE -> entityProvenanceTool(pair);
                        default -> throw new IllegalArgumentException("unknown tool: " + name);
                    });
        }
        return tools;
    }

    private ToolDef recallTool(PairKey pair) {
        return new ToolDef(
                RECALL,
                "Search everything known, ranked by relevance. Returns conclusions with how often each"
                        + " has been re-derived and when it was last confirmed. Start here.",
                schema("query", "What to look for, in the language of the source conversation."),
                arguments -> {
                    JsonNode node = parse(arguments);
                    var response =
                            recall.recall(
                                    new RecallRequest(
                                            pair, node.path("query").asText(""), limit(node), Filter.ALL, null, false));
                    return renderHits(response.hits());
                });
    }

    private ToolDef searchMessagesTool(PairKey pair, String sessionName) {
        return new ToolDef(
                SEARCH_MESSAGES,
                "Keyword search over the original messages. Supports \"quoted phrases\" and -exclusion."
                        + " Use when you need the exact wording rather than a conclusion.",
                schema("query", "Keywords to search for."),
                arguments -> {
                    JsonNode node = parse(arguments);
                    return renderMessages(
                            messages.searchText(
                                    pair.workspaceName(),
                                    pair.observer(),
                                    sessionName,
                                    node.path("query").asText(""),
                                    limit(node)));
                });
    }

    private ToolDef grepMessagesTool(PairKey pair, String sessionName) {
        return new ToolDef(
                GREP_MESSAGES,
                "Find messages containing an exact phrase, case-insensitively. Literal text, not a"
                        + " regular expression.",
                schema("text", "The exact phrase to find."),
                arguments -> {
                    JsonNode node = parse(arguments);
                    return renderMessages(
                            messages.grep(
                                    pair.workspaceName(),
                                    pair.observer(),
                                    sessionName,
                                    node.path("text").asText(""),
                                    limit(node)));
                });
    }

    private ToolDef messagesByDateTool(PairKey pair, String sessionName) {
        return new ToolDef(
                MESSAGES_BY_DATE,
                "Messages sent in a date range. Use to establish what was said around a particular time.",
                """
                {"type":"object","properties":{
                  "from":{"type":"string","description":"ISO-8601 instant, inclusive."},
                  "to":{"type":"string","description":"ISO-8601 instant, exclusive."},
                  "limit":{"type":"integer"}},
                 "required":["from","to"],"additionalProperties":false}
                """,
                arguments -> {
                    JsonNode node = parse(arguments);
                    return renderMessages(
                            messages.byDateRange(
                                    pair.workspaceName(),
                                    pair.observer(),
                                    sessionName,
                                    instant(node.path("from").asText(), Instant.EPOCH),
                                    instant(node.path("to").asText(), Instant.now()),
                                    limit(node)));
                });
    }

    private ToolDef searchTemporalTool(PairKey pair) {
        return new ToolDef(
                SEARCH_TEMPORAL,
                "Search what is known, restricted to conclusions last confirmed within a date range."
                        + " Use to tell a superseded fact from a current one.",
                """
                {"type":"object","properties":{
                  "query":{"type":"string"},
                  "from":{"type":"string","description":"ISO-8601 instant, inclusive."},
                  "to":{"type":"string","description":"ISO-8601 instant, exclusive."},
                  "limit":{"type":"integer"}},
                 "required":["query","from","to"],"additionalProperties":false}
                """,
                arguments -> {
                    JsonNode node = parse(arguments);
                    Filter window =
                            Filter.and(
                                    new Filter.Cmp("last_reinforced_at", FilterOp.GTE, node.path("from").asText()),
                                    new Filter.Cmp("last_reinforced_at", FilterOp.LT, node.path("to").asText()));
                    var response =
                            recall.recall(
                                    new RecallRequest(
                                            pair, node.path("query").asText(""), limit(node), window, null, false));
                    return renderHits(response.hits());
                });
    }

    private ToolDef reasoningChainTool(PairKey pair) {
        return new ToolDef(
                REASONING_CHAIN,
                "Why something is believed: the facts a conclusion was derived from, and anything"
                        + " derived from it in turn.",
                schema("conclusion_id", "The id of a conclusion returned by another tool."),
                arguments -> {
                    JsonNode node = parse(arguments);
                    String id = node.path("conclusion_id").asText("");
                    return conclusions
                            .find(pair.workspaceName(), id)
                            .map(
                                    conclusion -> {
                                        var trace = provenance.forConclusion(pair, conclusion);
                                        StringBuilder sb = new StringBuilder();
                                        sb.append("conclusion: ").append(conclusion.content()).append('\n');
                                        sb.append("derived from:\n");
                                        if (trace.premises().isEmpty()) {
                                            sb.append("  (stated directly, not derived)\n");
                                        }
                                        trace.premises()
                                                .forEach(p -> sb.append("  [").append(p.id()).append("] ")
                                                        .append(p.content()).append('\n'));
                                        sb.append("supports:\n");
                                        List<Conclusion> dependents =
                                                provenance.dependents(pair.workspaceName(), id);
                                        if (dependents.isEmpty()) {
                                            sb.append("  (nothing)\n");
                                        }
                                        dependents.forEach(
                                                d -> sb.append("  [").append(d.id()).append("] ")
                                                        .append(d.content()).append('\n'));
                                        sb.append("original messages:\n");
                                        sb.append(renderMessages(trace.sourceMessages()));
                                        return sb.toString();
                                    })
                            .orElse("No conclusion with id " + id + ".");
                });
    }

    private ToolDef entityProvenanceTool(PairKey pair) {
        return new ToolDef(
                ENTITY_PROVENANCE,
                "Everything known that mentions a named person, place, organisation or product, with"
                        + " the original messages behind each.",
                schema("entity", "The name, as it appears in the conversation."),
                arguments -> {
                    JsonNode node = parse(arguments);
                    String name = node.path("entity").asText("");
                    return provenance
                            .forEntity(pair, name, DEFAULT_LIMIT)
                            .map(
                                    result -> {
                                        StringBuilder sb = new StringBuilder();
                                        sb.append("entity: ").append(result.entity().nameDisplay()).append('\n');
                                        result.conclusions()
                                                .forEach(
                                                        c -> {
                                                            sb.append("- [").append(c.conclusion().id()).append("] ")
                                                                    .append(c.conclusion().content()).append('\n');
                                                            c.sourceMessages()
                                                                    .forEach(m -> sb.append("    said: ")
                                                                            .append(m.peerName()).append(": ")
                                                                            .append(m.content()).append('\n'));
                                                        });
                                        return sb.toString();
                                    })
                            .orElse("Nothing known about an entity called " + name + ".");
                });
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private static String renderHits(List<ScoredConclusion> hits) {
        if (hits.isEmpty()) {
            return "No matches.";
        }
        StringBuilder sb = new StringBuilder();
        for (ScoredConclusion hit : hits) {
            Conclusion conclusion = hit.conclusion();
            sb.append('[')
                    .append(conclusion.id())
                    .append("] ")
                    .append(conclusion.content())
                    .append("  (confirmed ")
                    .append(conclusion.timesDerived())
                    .append(conclusion.timesDerived() == 1 ? " time, last " : " times, last ")
                    .append(conclusion.lastReinforcedAt())
                    .append(", ")
                    .append(conclusion.level().wire())
                    .append(")\n");
        }
        return sb.toString();
    }

    private static String renderMessages(List<Message> found) {
        if (found.isEmpty()) {
            return "No matches.";
        }
        StringBuilder sb = new StringBuilder();
        for (Message message : found) {
            sb.append(message.createdAt())
                    .append(' ')
                    .append(message.peerName())
                    .append(": ")
                    .append(message.content())
                    .append('\n');
        }
        return sb.toString();
    }

    // ── Argument handling ───────────────────────────────────────────────────

    private static String schema(String field, String description) {
        return """
               {"type":"object","properties":{
                 "%s":{"type":"string","description":"%s"},
                 "limit":{"type":"integer","description":"How many results, at most 50."}},
                "required":["%s"],"additionalProperties":false}
               """
                .formatted(field, description, field);
    }

    private static JsonNode parse(String arguments) {
        try {
            return MAPPER.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("arguments are not valid JSON: " + arguments);
        }
    }

    /** Clamped rather than validated: a model asking for 10000 results should get 50, not an error. */
    private static int limit(JsonNode node) {
        int requested = node.path("limit").asInt(DEFAULT_LIMIT);
        return Math.max(1, Math.min(MAX_LIMIT, requested));
    }

    private static Instant instant(String raw, Instant fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            try {
                return java.time.LocalDate.parse(raw).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException ignored) {
                return fallback;
            }
        }
    }
}
