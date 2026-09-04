package at.aimon.memory.engine.dialectic;

import java.util.List;
import java.util.Locale;

import at.aimon.memory.core.MemoryException;

/**
 * How hard the dialectic is allowed to work.
 *
 * <p>Both the iteration cap and the toolset vary. Capping iterations alone is not enough: give a
 * model seven tools and it will use them, so a cheap question with an expensive toolset burns its
 * budget exploring. The lower levels hand over a deliberately small kit.
 *
 * <p>{@code MINIMAL} has one tool, and it is Tier 1 — which for most questions is the entire answer.
 */
public enum ReasoningLevel {
    MINIMAL(1, List.of(ToolRegistry.RECALL)), LOW(3,
            List.of(ToolRegistry.RECALL, ToolRegistry.SEARCH_MESSAGES)), MEDIUM(6,
                    List.of(ToolRegistry.RECALL, ToolRegistry.SEARCH_MESSAGES, ToolRegistry.GREP_MESSAGES,
                            ToolRegistry.MESSAGES_BY_DATE)), HIGH(
                                    10,
                                    List.of(ToolRegistry.RECALL, ToolRegistry.SEARCH_MESSAGES,
                                            ToolRegistry.GREP_MESSAGES, ToolRegistry.MESSAGES_BY_DATE,
                                            ToolRegistry.SEARCH_TEMPORAL, ToolRegistry.REASONING_CHAIN)), MAX(
                                                    16,
                                                    List.of(ToolRegistry.RECALL, ToolRegistry.SEARCH_MESSAGES,
                                                            ToolRegistry.GREP_MESSAGES, ToolRegistry.MESSAGES_BY_DATE,
                                                            ToolRegistry.SEARCH_TEMPORAL, ToolRegistry.REASONING_CHAIN,
                                                            ToolRegistry.ENTITY_PROVENANCE));

    private final int maxIterations;
    private final List<String> toolNames;

    ReasoningLevel(int maxIterations, List<String> toolNames) {
        this.maxIterations = maxIterations;
        this.toolNames = toolNames;
    }

    public int maxIterations() {
        return maxIterations;
    }

    public List<String> toolNames() {
        return toolNames;
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ReasoningLevel fromWire(String wire) {
        if (wire == null || wire.isBlank()) {
            return MEDIUM;
        }
        for (ReasoningLevel level : values()) {
            if (level.wire().equals(wire.toLowerCase(Locale.ROOT))) {
                return level;
            }
        }
        throw new MemoryException("bad_reasoning_level", "unknown reasoning level: " + wire);
    }
}
