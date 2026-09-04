package at.aimon.memory.llm.replay;

import at.aimon.memory.llm.LlmException;

/**
 * Replay mode found no fixture for a call.
 *
 * <p>This is a test failure by design. Prompts drift silently otherwise: an edit that changes what
 * the model is asked would sail through a suite that quietly fell back to a live call or an empty
 * answer. A miss says exactly which call changed and how to re-record it.
 */
public class FixtureMissException extends LlmException {

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
}
