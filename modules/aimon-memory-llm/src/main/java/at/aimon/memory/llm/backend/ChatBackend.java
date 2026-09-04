package at.aimon.memory.llm.backend;

import java.util.stream.Stream;

/**
 * The one provider primitive.
 *
 * <p>Everything above this line — structured output, tool loops, fallback chains — is provider-neutral
 * code written once. Everything below is per-provider translation. Recording sits exactly here, which
 * is why a multi-step tool loop replays step by step without the harness knowing what a loop is.
 */
public interface ChatBackend {

    ChatResponse chat(ChatCall call);

    Stream<String> stream(ChatCall call);

    String defaultModel();

    String providerName();
}
