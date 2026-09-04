package at.aimon.memory.engine.derive;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.model.Message;

class MessageFormatterTest {

    private static Message message(String peer, String content, String instant) {
        return new Message(1, "ws", "s1", peer, content, 1, 3, Map.of(), Instant.parse(instant));
    }

    @Test
    void formatsTimestampPeerAndContent() {
        assertThat(MessageFormatter.formatOne(message("alice", "hello", "2026-08-31T12:34:56Z")))
                .isEqualTo("2026-08-31 12:34:56 alice: hello");
    }

    /**
     * UTC, not the JVM default. The formatted batch is hashed into the fixture key, so a worker in a
     * different timezone would miss every fixture the CI machine recorded.
     */
    @Test
    void rendersInUtcRegardlessOfTheDefaultTimezone() {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"));
            assertThat(MessageFormatter.formatOne(message("alice", "hello", "2026-08-31T12:34:56Z")))
                    .contains("12:34:56");
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    @Test
    void joinsABatchInOrderWithoutATrailingNewline() {
        String formatted = MessageFormatter.format(List.of(message("alice", "first", "2026-08-31T12:00:00Z"),
                message("bob", "second", "2026-08-31T12:00:05Z")));

        assertThat(formatted).isEqualTo("2026-08-31 12:00:00 alice: first\n2026-08-31 12:00:05 bob: second");
        assertThat(MessageFormatter.format(List.of())).isEmpty();
    }
}
