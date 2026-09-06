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
     *
     * <p>This method answers only for the failures this build words itself. What a caller is shown
     * when the failure came from somewhere else — a driver, a library, the JDK — is
     * {@link #publicMessageOf(Throwable)}, and it is not that exception's message.
     */
    public String publicMessage() {
        return getMessage();
    }

    /**
     * What a caller is told about a failure nothing in this build worded.
     *
     * <p>Deliberately one string rather than a classification. Naming the exception's type would say
     * more — {@code bad_json} keeps the target type name on the wire for exactly that reason — but
     * that argument holds because the set of names it can print is this build's own records. The set
     * here is every exception class on the classpath, which cannot be enumerated and so cannot be
     * audited; {@code HikariPool$PoolInitializationException} is already a sentence about the
     * deployment. {@code ApiExceptionHandler.unexpected} made the same call for the same population.
     */
    private static final String INTERNAL_FAILURE = "an internal failure; see the server log";

    /**
     * {@link #publicMessage()} for an arbitrary throwable, so a general {@code catch (RuntimeException)}
     * can apply the rule without first testing the type.
     *
     * <p>Anything that is not a {@code MemoryException} is summarised rather than quoted, because the
     * rule this method exists to apply is not "the message minus the awkward ones" — it is <b>the
     * caller is shown what was written for the caller</b>, and being a {@code MemoryException} is what
     * says a message was. Everything else was worded by a driver, a library or the JDK for whoever
     * reads the log. It used to return {@code getMessage()} here, which quietly inverted the rule for
     * the whole population it could not vouch for: a Postgres error alone carries the failing
     * statement, the constraint, the relation and — for a not-null or check violation — {@code Detail:
     * Failing row contains (…)}, the row itself.
     *
     * <p>Measured, in a dream whose write hit a not-null column: {@code GET /v1/workspaces/ws/dreams}
     * answered <b>200</b> with a 1,462-byte body whose {@code error} held the whole {@code INSERT},
     * every column name, the dedup scope from the {@code ON CONFLICT} clause, and a failing row
     * carrying the derived conclusion's text, its normalised and analysed forms, its hash and the head
     * of its vector. The same exception through {@code ApiExceptionHandler.constraint} was 124 bytes:
     * a code and one sentence. One door refused it; the other did not.
     */
    public static String publicMessageOf(Throwable e) {
        return e instanceof MemoryException memory ? memory.publicMessage() : INTERNAL_FAILURE;
    }
}
