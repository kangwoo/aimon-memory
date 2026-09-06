package at.aimon.memory.llm.replay;

import at.aimon.memory.llm.LlmException;

/**
 * Replay mode found no fixture for a call.
 *
 * <p>This is a test failure by design. Prompts drift silently otherwise: an edit that changes what
 * the model is asked would sail through a suite that quietly fell back to a live call or an empty
 * answer. A miss says exactly which call changed and how to re-record it.
 *
 * <p>Which is why this is the one message in the build written for somebody other than the caller,
 * and the only {@link #publicMessage()} override. Replay is not opt-in — {@code
 * LlmMode.fromEnvironment} returns {@code REPLAY} unless the mode is set, and {@code
 * MemoryConfiguration.llmClient} wraps every configured provider in {@code RecordingChatBackend}
 * unconditionally — so a deployment that sets a provider and forgets the mode answers real requests
 * with this. {@link #getMessage()} keeps the whole diagnostic for the developer and the log;
 * {@link #publicMessage()} is what a caller is allowed to see.
 */
public class FixtureMissException extends LlmException {

    /** No detail at all: a caller can act on the code, and nothing here is theirs to read. */
    public static final String PUBLIC_MESSAGE = "no recorded LLM fixture matches this request; see the server log";

    public FixtureMissException(String key, String canonicalRequest, java.nio.file.Path directory) {
        super("fixture_miss", """
                No LLM fixture for key %s.

                Looked in: %s

                The request that produced this key:
                %s

                If the prompt changed on purpose, re-record it:
                  AIMON_MEMORY_LLM_MODE=record OPENAI_API_KEY=... ./gradlew test --tests '<the test>'
                """.formatted(key, directory, canonicalRequest));
    }

    @Override
    public String publicMessage() {
        return PUBLIC_MESSAGE;
    }
}
