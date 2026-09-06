package at.aimon.memory.core;

/** Base for every failure aimon-memory raises deliberately. Carries a stable code for API mapping. */
public class MemoryException extends RuntimeException {

    private final String code;

    public MemoryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public MemoryException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * The part of this failure a caller may be shown.
     *
     * <p>Almost always the whole message, because a message is written for whoever made the request:
     * {@code store_failed} naming a workspace or a session repeats values the caller just sent. The
     * exception is a message written for somebody else — {@code FixtureMissException} is a diagnostic
     * for a developer reading a failed {@code ./gradlew test}, and inlines the whole assembled prompt
     * and an absolute server path — which overrides this with the part that is safe on the wire.
     *
     * <p>The split lives here rather than in {@code ApiExceptionHandler} because the handler is not
     * the only reader. {@code DreamerService} and {@code DreamConsumer} put a failed dream's message
     * in {@code dreams.error}, and {@code Dtos.DreamResponse} returns that column in a <b>200</b> — so
     * a rule enforced only at the 5xx boundary is a rule with a second way out. Everything that copies
     * a message towards a caller uses this; the log keeps {@link #getMessage()} in full.
     */
    public String publicMessage() {
        return getMessage();
    }

    /**
     * {@link #publicMessage()} for an arbitrary throwable, so a general {@code catch (RuntimeException)}
     * can apply the rule without first testing the type.
     */
    public static String publicMessageOf(Throwable e) {
        return e instanceof MemoryException memory ? memory.publicMessage() : e.getMessage();
    }
}
