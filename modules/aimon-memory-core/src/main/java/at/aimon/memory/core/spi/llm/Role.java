package at.aimon.memory.core.spi.llm;

import java.util.Locale;

public enum Role {
    USER, ASSISTANT;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
