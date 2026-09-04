package at.aimon.memory.llm.replay;

import java.util.List;

/**
 * The on-disk record.
 *
 * @param canonicalRequest the exact string that was hashed; makes a miss diffable against the fixture
 *     that was expected to match
 * @param streamChunks non-null only for recorded streaming calls
 */
public record LlmFixture(String key, String canonicalRequest, String responseJson, List<String> streamChunks,
        String recordedAt) {
}
