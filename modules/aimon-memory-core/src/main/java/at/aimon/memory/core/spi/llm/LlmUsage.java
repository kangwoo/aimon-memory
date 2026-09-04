package at.aimon.memory.core.spi.llm;

public record LlmUsage(int promptTokens, int completionTokens) {

    public static final LlmUsage NONE = new LlmUsage(0, 0);

    public int total() {
        return promptTokens + completionTokens;
    }

    public LlmUsage plus(LlmUsage other) {
        return new LlmUsage(promptTokens + other.promptTokens, completionTokens + other.completionTokens);
    }
}
