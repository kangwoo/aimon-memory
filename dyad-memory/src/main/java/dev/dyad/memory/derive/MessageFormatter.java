package dev.dyad.memory.derive;

import dev.dyad.core.model.Message;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders a message batch for the extraction prompt.
 *
 * <p>{@code YYYY-MM-DD HH:mm:ss peer: content}. Timestamps are included because half the useful
 * conclusions are temporal — "started a new job", "moved" — and a model with no clock cannot order
 * them. UTC, so the same batch renders identically wherever the worker runs, which the fixtures
 * depend on.
 */
public final class MessageFormatter {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private MessageFormatter() {}

    public static String format(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            sb.append(FORMAT.format(message.createdAt()))
                    .append(' ')
                    .append(message.peerName())
                    .append(": ")
                    .append(message.content())
                    .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    public static String formatOne(Message message) {
        return format(List.of(message));
    }
}
