package at.aimon.memory.llm;

import java.util.Locale;

/**
 * How LLM calls are resolved.
 *
 * <p>The harness exists because a non-deterministic dependency makes regression testing impossible.
 * With it, CI runs the real code paths against frozen responses, and a prompt change produces a
 * fixture miss — which is the point, not an inconvenience. A prompt edit that nobody notices is
 * exactly the failure this prevents.
 */
public enum LlmMode {
    /** Call the provider and write the response to a fixture. */
    RECORD,
    /** Serve from fixtures; a miss is a failure. The only mode CI runs. */
    REPLAY,
    /** Call the provider, record nothing. Local and manual. */
    LIVE;

    public static final String ENV = "AIMON_MEMORY_LLM_MODE";

    public static LlmMode fromEnvironment() {
        String raw = System.getProperty("aimon.memory.llm.mode", System.getenv(ENV));
        return raw == null || raw.isBlank() ? REPLAY : parse(raw);
    }

    public static LlmMode parse(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "record" -> RECORD;
            case "replay" -> REPLAY;
            case "live" -> LIVE;
            default -> throw new LlmException("bad_llm_mode", "unknown " + ENV + ": " + raw);
        };
    }

    public boolean callsProvider() {
        return this != REPLAY;
    }
}
